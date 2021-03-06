///////////////////////////////////////////////////////////////////////////////
//  Document.scala
//
//  Copyright (C) 2010-2014 Ben Wing, The University of Texas at Austin
//  Copyright (C) 2011, 2012 Stephen Roller, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////

package opennlp.textgrounder
package gridlocate

import scala.util.matching.Regex
import scala.util.control.Breaks._

import java.io._

import util.collection._
import util.coord.CoordHandler
import util.textdb._
import util.textdb.TextDB._
import util.spherical._
import util.error._
import util.experiment._
import util.io._
import util.print._
import util.serialize.TextSerializer
import util.string.capfirst
import util.textdb.Row

import langmodel._

import util.debug._


/////////////////////////////////////////////////////////////////////////////
//                            Document loading                             //
/////////////////////////////////////////////////////////////////////////////


case class RawDoc(row: Row, domain: String, importance: Double)

/**
 * Object encapsulating the result of attempting to read a document from
 * some external source, or convert such a document to another format.
 * If the processing is successful, there will be a document of type
 * `TDoc` stored in `maybedoc`; otherwise, perhaps not. The original
 * external source of the document is stored in `filehand`, `file` and
 * `line`. The status of the operation is stored in `status`, and will
 * be one of "processed" (i.e. successful), "skipped" (document was
 * skipped at this or a preceding stage for reasons other than an error)
 * or "bad" (an error occurred during processing, at this or a preceding
 * stage). A document in `maybedoc` will always be present when `status` ==
 * "processed" and may be present when `status` == "skipped", depending
 * on whether skipping happened at the most recent stage or not.
 * `reason` is a string indicating the reason why something is bad or
 * skipped; and `docdesc` is a description of the document, useful
 * especially for bad or skipped documents, where it generally describes
 * what the document looked like at the point it was skipped or
 * discovered bad.
 *
 * Note that this behaves somewhat like Option[TDoc], and similarly
 * it can be converted to a different document format using
 * `map_result` or `map_result_all`, which converts successfully-processed
 * documents (and in the case of `map_result_all`, any document stored
 * in `maybedoc`, including when `status` == "skipped"), and propagates
 * the status and other properties.
 */
case class DocStatus[TDoc](
  filehand: FileHandler,
  file: String,
  lineno: Long,
  maybedoc: Option[TDoc],
  status: String,
  reason: String,
  docdesc: String
) {
  require(status match {
    case "processed" => maybedoc != None
    case "bad" => maybedoc == None
    case "skipped" => true
  })

  /**
   * Create a new DocStatus for a new type of document by mapping the
   * existing wrapped document, if it exists, through a function `f`.
   * Only maps an existing document if `status` == "processed". Otherwise,
   * the new DocStatus will have None as the value of `maybedoc` (even
   * if the existing one had `status` == "skipped" and a wrapped document).
   */
  def map_result[UDoc](f: TDoc => (Option[UDoc], String, String, String)) = {
    status match {
      case "processed" => {
        val (doc2, status2, reason2, docdesc2) = f(maybedoc.get)
        DocStatus(filehand, file, lineno, doc2, status2, reason2, docdesc2)
      }
      case _ =>
        DocStatus[UDoc](filehand, file, lineno, None, status, reason, docdesc)
    }
  }

  /**
   * Similar to `map_result` but also maps wrapped documents even when
   * `status` == "skipped". The status is passed into the function.
   */
  def map_all_result[UDoc](
    f: (String, TDoc) => (Option[UDoc], String, String, String)
  ) = {
    (status, maybedoc) match {
      case (_, None) =>
        DocStatus[UDoc](filehand, file, lineno, None, status, reason, docdesc)
      case _ => {
        val (doc2, status2, reason2, docdesc2) = f(status, maybedoc.get)
        DocStatus(filehand, file, lineno, doc2, status2, reason2, docdesc2)
      }
    }
  }

  /**
   * Create a new DocStatus for a new type of document by mapping the
   * existing wrapped document, if it exists, through a function `f`.
   * Only maps an existing document if `status` == "processed". Otherwise,
   * the new DocStatus will have None as the value of `maybedoc` (even
   * if the existing one had `status` == "skipped" and a wrapped document).
   * This is a very simple function for mapping the document. Use
   * `map_result` for more complicated mapping that allows the new wrapped
   * document to have the value of None and new values for `status`,
   * `reason` and `docdesc` to be given.
   */
  def map_processed[UDoc](f: TDoc => UDoc) = {
    status match {
      case "processed" => {
        val doc2 = f(maybedoc.get)
        DocStatus(filehand, file, lineno, Some(doc2), status, reason, docdesc)
      }
      case _ =>
        DocStatus[UDoc](filehand, file, lineno, None, status, reason, docdesc)
    }
  }

  /**
   * Similar to `map_processed` but also maps wrapped documents even when
   * `status` == "skipped". This is a very simple function for mapping the
   * document. Use `map_all_result` for more complicated mapping that allows
   * the new wrapped document to have the value of None and new values for
   * `status`, `reason` and `docdesc` to be given.
   */
  def map_all[UDoc](f: TDoc => UDoc) = {
    (status, maybedoc) match {
      case (_, None) =>
        DocStatus[UDoc](filehand, file, lineno, None, status, reason, docdesc)
      case _ => {
        val doc2 = f(maybedoc.get)
        DocStatus(filehand, file, lineno, Some(doc2), status, reason, docdesc)
      }
    }
  }

  /**
   * Do something side-effectual to a wrapped document, by calling `f`.
   * As for `map_result`, this only processes wrapped documents when
   * `status` = "processed", never when `status` = "skipped".
   */
  def foreach(f: TDoc => Unit) {
    status match {
      case "processed" => f(maybedoc.get)
      case _ => ()
    }
  }

  /**
   * Do something side-effectual to any wrapped document, by calling `f`.
   * This results to `foreach` as `map_all_result` relates to `map_result`,
   * i.e. it will call `f` on all wrapped documents, even when
   * status = "skipped".
   */
  def foreach_all(f: (String, TDoc) => Unit) {
    (status, maybedoc) match {
      case (_, None) => ()
      case _ => f(status, maybedoc.get)
    }
  }
}

/******** Counters to track what's going on ********/

/**
 * An object used to track (i.e. maintain status on) the number of documents
 * read or generated from a given source file in a document corpus,
 * including successes, skipped records, and various types of failures.
 * This uses the concept of a "counter", as found in Hadoop (but also
 * implemented outside of Hadoop). A counter can be thought of as a remote
 * variable (i.e. state that may be shared between different machines in a
 * network) that can only hold a single non-negative integer and changes
 * to the integer can only involve incrementing it; i.e. they're useful for
 * counting occurrences of events but not too much else.
 *
 * @tparam T type of the document being processed (usually either
 *   `RawDoc` or `GridDoc`)
 * @param shortfile A version of the source file name, used in logging
 *   messages; may omit directories or other information shared among
 *   multiple file names.
 * @param driver Object used to transmit counters externally, e.g. to
 *   log files, to the Hadoop job tracker machine, etc.
 */
class DocCounterTracker[T](
  shortfile: String,
  driver: ExperimentDriverStats
) {
  def get_file_counter_name(counter: String) =
    "byfile." + shortfile + "." + counter

  def increment_counter(counter: String, value: Long = 1) {
    if (driver != null) {
      val file_counter = get_file_counter_name(counter)
      driver.increment_task_counter(file_counter, value)
      driver.increment_task_counter(counter, value)
      driver.increment_local_counter(file_counter, value)
      driver.increment_local_counter(counter, value)
    }
  }

  def increment_document_counter(counter: String) {
    increment_counter(counter)
    increment_counter("documents.total")
  }

  def record_status(status: DocStatus[T]) {
    status.status match {
      case "bad" => increment_document_counter("documents.bad")
      case "skipped" => increment_document_counter("documents.skipped")
      case "processed" => increment_document_counter("documents.processed")
    }
  }

  def print_status(status: DocStatus[T]) {
    status.status match {
      case "skipped" =>
        errprint("Skipped document %s because %s", status.docdesc, status.reason)
      case "bad" =>
        errprint("Unable to load document %s because %s", status.docdesc,
          status.reason)
      case _ => {}
    }
  }

  def handle_status(status: DocStatus[T]): Option[T] = {
    record_status(status)
    print_status(status)
    status.status match {
      case "processed" => status.maybedoc
      case _ => None
    }
  }

  def note_document_counters(file: String) {
    def note(counter: String, english: String) {
      val file_counter = get_file_counter_name(counter)
      val value = driver.get_task_counter(file_counter)
      errprint("Number of %s for file %s: %s", english, file,
        value)
    }

    note("documents.processed", "documents processed")
    note("documents.skipped", "documents skipped")
    note("documents.bad", "bad documents")
    note("documents.total", "total documents")
  }
}

/**
 * A factory object for generating `DocCounterTracker` objects, for
 * tracking the number of successful/skipped/failed documents read from
 * a corpus source file. There will be one such tracker per source file.
 * See `DocCounterTracker` for more info, e.g. the concept of "counter".
 *
 * @tparam T type of the document being processed (usually either
 *   `RawDoc` or `GridDoc`)
 * @param driver Object used to transmit counters externally, e.g. to
 *   log files, to the Hadoop job tracker machine, etc.
 */
class DocCounterTrackerFactory[T](driver: ExperimentDriverStats) {
  def filename_to_counter_name(filehand: FileHandler, file: String) = {
    var (_, tail) = filehand.split_filename(file)
    breakable {
      while (true) {
        val newtail = """\.[a-z0-9]*$""".r.replaceAllIn(tail, "")
        if (newtail == tail) break
        tail = newtail
      }
    }
    """[^a-zA-Z0-9]""".r.replaceAllIn(tail, "_")
  }

  def create_tracker(shortname: String) =
    new DocCounterTracker[T](shortname, driver)

  def process_statuses(statuses: Iterator[DocStatus[T]]) = {
    val byfile = new GroupByIterator(statuses,
      (stat: DocStatus[T]) => (stat.filehand, stat.file))
    (for (((filehand, file), file_statuses) <- byfile) yield {
      val tracker = create_tracker(filename_to_counter_name(filehand, file))
      val results = file_statuses.flatMap(tracker.handle_status(_))
      if (debug("per-document")) {
        val output_stats =
          new SideEffectIterator({ tracker.note_document_counters(file) })
        results ++ output_stats
      } else
        results
    }).flatten
  }
}


/////////////////////////////////////////////////////////////////////////////
//                          Document language models                       //
/////////////////////////////////////////////////////////////////////////////

/**
 * A class holding the language models associated with a document.
 * There may be a separate one needed for reranking (e.g. holding n-grams
 * when the main ranker uses unigrams).
 */
class DocLangModel(
  val grid_lm: LangModel,
  val rerank_lm: LangModel
) extends Iterable[LangModel] {
  def iterator =
    if (grid_lm != rerank_lm)
      Iterator(grid_lm, rerank_lm)
    else
      Iterator(grid_lm)

  def add_language_model(other: DocLangModel, partial: Double = 1.0) {
    assert_==(this.size, other.size, "model size")
    (this zip other).map {
      case (thislm, otherlm) =>
        thislm.add_language_model(otherlm, partial)
    }
  }

  def add_gram(gram: Gram, count: GramCount) {
    foreach(_.add_gram(gram, count))
  }

  def finished = forall(_.finished)

  def finish_before_global() {
    foreach(_.finish_before_global())
  }

  def finish_after_global() {
    foreach(_.finish_after_global())
  }
}

/**
 * A class holding the language model factories associated with a document.
 * There may be a separate one needed for reranking (e.g. holding n-grams
 * when the main ranker uses unigrams).
 */
class DocLangModelFactory(
  val grid_lang_model_factory: LangModelFactory,
  val rerank_lang_model_factory: LangModelFactory
) extends Iterable[LangModelFactory] {
  def iterator =
    if (grid_lang_model_factory != rerank_lang_model_factory)
      Iterator(grid_lang_model_factory, rerank_lang_model_factory)
    else
      Iterator(grid_lang_model_factory)

  def create_lang_model = {
    val grid_lm = grid_lang_model_factory.create_lang_model
    new DocLangModel(grid_lm,
      if (grid_lang_model_factory != rerank_lang_model_factory)
        rerank_lang_model_factory.create_lang_model
      else
        grid_lm
    )
  }

  def finish_global_backoff_stats() {
    foreach(_.finish_global_backoff_stats())
  }
}


/////////////////////////////////////////////////////////////////////////////
//                              Document factories                         //
/////////////////////////////////////////////////////////////////////////////

/**
 * Factory for creating documents and maintaining statistics and certain
 * other info about them.
 */
abstract class GridDocFactory[Co : TextSerializer : CoordHandler](
  val driver: GridLocateDriver[Co],
  val lang_model_factory: DocLangModelFactory
) {
  // Example of using TaskCounterWrapper directly for non-split values.
  // val num_documents = new driver.TaskCounterWrapper("num_documents")

  /** # of records seen in each split. */
  val num_records_by_split =
    driver.countermap("num_records_by_split")
  /** # of records skipped in each split due to errors */
  val num_error_skipped_records_by_split =
    driver.countermap("num_error_skipped_records_by_split")
  /** # of documents seen in each split.  This does not include skipped
    * records (see above).  */
  val num_documents_by_split =
    driver.countermap("num_documents_by_split")
  /** # of documents seen in each split skipped because lacking coordinates.
    */
  val num_documents_skipped_because_lacking_coordinates_by_split =
    driver.countermap("num_documents_skipped_because_lacking_coordinates_by_split")
  /** # of training documents seen in each split skipped because lacking
    * coordinates. */
  val num_would_be_training_documents_skipped_because_lacking_coordinates_by_split =
    driver.countermap("num_would_be_training_documents_skipped_because_lacking_coordinates_by_split")
  /** # of training documents seen in each split. Note that some of the
    * times where training documents are processed, they won't be recorded
    * here. This happens specifically when a set of training documents need
    * to be processed multiple times, e.g. during reranking or when
    * handling a K-d grid.
    */
  val num_training_documents_by_split =
    driver.countermap("num_training_documents_by_split")
  /** # of documents in each split with coordinates. */
  val num_documents_with_coordinates_by_split =
    driver.countermap("num_documents_with_coordinates_by_split")
  /** # of training documents in each split with coordinates. See comments
    * above about when training documents are recorded in these stats. */
  val num_training_documents_with_coordinates_by_split =
    driver.countermap("num_training_documents_with_coordinates_by_split")
  /** # of word tokens for documents seen in each split.  This does not
    * include skipped records (see above). */
  val word_tokens_of_documents_by_split =
    driver.countermap("word_tokens_of_documents_by_split")
  /** # of word tokens for documents seen in each split skipped because
    * lacking coordinates (see above). */
  val word_tokens_of_documents_skipped_because_lacking_coordinates_by_split =
    driver.countermap("word_tokens_of_documents_skipped_because_lacking_coordinates_by_split")
  /** # of word tokens for training documents seen in each split skipped
    * because lacking coordinates. */
  val word_tokens_of_would_be_training_documents_skipped_because_lacking_coordinates_by_split =
    driver.countermap("word_tokens_of_would_be_training_documents_skipped_because_lacking_coordinates_by_split")
  /** # of word tokens for training documents seen in each split. See
    * comments above about when training documents are recorded in these
    * stats. */
  val word_tokens_of_training_documents_by_split =
    driver.countermap("word_tokens_of_training_documents_by_split")
  /** # of word tokens for documents in each split with coordinates. */
  val word_tokens_of_documents_with_coordinates_by_split =
    driver.countermap("word_tokens_of_documents_with_coordinates_by_split")
  /** # of word tokens for training documents in each split with coordinates.
    */
  val word_tokens_of_training_documents_with_coordinates_by_split =
    driver.countermap("word_tokens_of_training_documents_with_coordinates_by_split")

  def coord_handler = implicitly[CoordHandler[Co]]

  /**
   * Create, initialize and return a document from the given raw document.
   * Throw an error if errors found.
   *
   * @param rawdoc Raw document describing record loaded from a corpus
   * @param lang_model Language model(s) describing the document, as
   *   computed from data in the raw document
   */
  protected def create_document(rawdoc: RawDoc, lang_model: DocLangModel
  ): GridDoc[Co]

  /* Record a training document in any subsidiary factores, subclasses, etc.
   * Currently used only by Wikipedia documents. (Not the same as
   * `record_training_document_in_factory`, which records diagnostic
   * statistics in the factory itself.)
   */
  protected def record_training_document_in_subfactory(doc: GridDoc[Co]) { }

  /**
   * Convert a raw document (directly describing the fields of the document,
   * as read from a textdb database or similar) to an actual document object.
   * Both the raw and processed document objects are encapsulated in a
   * `DocStatus` object indicating whether the processing that generated the
   * object was successful or not, and if not, why not. Eventually, the
   * processed document objects themselves are extracted (using
   * `document_statuses_to_documents`); this throws away failures, but in the
   * process logs them appropriately. By encapsulating the success or failure
   * this way, we allow the logging to be delayed until the point where a
   * given document is actually consumed, for proper sequencing.
   *
   * We separate out raw (class `RawDoc`) and processed documents
   * (class `GridDoc`) because in some circumstances we may need to
   * process the raw documents multiple times in different ways, e.g. when
   * creating splits for cross-validation (as done when training a reranker).
   *
   * @param rawdoc Raw document as directly read from a corpus.
   * @param skip_no_coord If true, skip documents without a coordinate.
   * @param note_globally Whether to incorporate the document's word
   *  counts into the global smoothing statistics. (FIXME, this should be
   *  handled separately, as above.)
   * @return a DocStatus describing the document.
   */
  def raw_document_to_document_status(rawdocstat: DocStatus[RawDoc],
      skip_no_coord: Boolean, note_globally: Boolean
      ): (DocStatus[(RawDoc, GridDoc[Co])]) = {
    rawdocstat map_result { rd =>
      try {
        val split = rd.row.gets_or_else("split", "unknown")
        num_records_by_split(split) += 1

        def catch_doc_validation[T](body: => T) = {
          if (debug("no-catch"))
            body
          else {
            try {
              body
            } catch {
              case e:Exception => {
                num_error_skipped_records_by_split(split) += 1
                if (debug("stack-trace") || debug("stacktrace"))
                  e.printStackTrace
                throw new DocValidationException(
                  s"Bad value for field: $e", Some(e))
              }
            }
          }
        }

        val grid_lm = catch_doc_validation {
          val counts = rd.row.gets(driver.grid_word_count_field)
          lang_model_factory.grid_lang_model_factory.builder.
            create_lang_model(counts, rd.domain, rd.importance)
        }
        val rerank_lm =
          if (driver.rerank_word_count_field == driver.grid_word_count_field)
            grid_lm
          else
            catch_doc_validation {
              val counts = rd.row.gets(driver.rerank_word_count_field)
              lang_model_factory.rerank_lang_model_factory.builder.
                create_lang_model(counts, rd.domain, rd.importance)
            }
        val lang_model = new DocLangModel(grid_lm, rerank_lm)
        val doc = catch_doc_validation {
          create_document(rd, lang_model)
        }
        // if (doc == None) // used to mean skipped
        // num_non_error_skipped_records_by_split(split) += 1
        assert_==(doc.split, split, "split")
        val tokens = doc.grid_lm.num_tokens
        num_documents_by_split(split) += 1
        word_tokens_of_documents_by_split(split) += tokens
        if (!doc.has_coord && skip_no_coord) {
          num_documents_skipped_because_lacking_coordinates_by_split(split) += 1
          word_tokens_of_documents_skipped_because_lacking_coordinates_by_split(split) += tokens
          (Some((rd, doc)), "skipped", "document has no coordinate",
            doc.title)
        } else {
          num_documents_with_coordinates_by_split(split) += 1
          word_tokens_of_documents_with_coordinates_by_split(split) += tokens
          if (note_globally) {
            // Don't use the eval set's language models in computing
            // global smoothing values and such, to avoid contaminating
            // the results (training on your eval set). In addition, if
            // this isn't the training or eval set, we shouldn't be
            // loading at all.
            //
            // Add the language model to the global stats before
            // eliminating infrequent words through
            // `finish_before_global`.
            doc.lang_model.foreach(lm => lm.factory.note_lang_model_globally(lm))
          }
          doc.lang_model.finish_before_global()
          (Some((rd, doc)), "processed", "", "")
        }
      } catch {
        case e:DocValidationException => {
          warning("Line %s: %s", rawdocstat.lineno, e.message)
          (None, "bad", "error validating document field", "")
        }
        case e:LangModelCreationException => {
          if (driver.grid_word_count_field ==
              driver.rerank_word_count_field)
            warning("Line %s: %s, word-counts field: %s",
              rawdocstat.lineno, e.message,
              rd.row.gets_or_else(driver.grid_word_count_field,
                "(missing word-counts field)"))
          else
            warning("Line %s: %s, grid word-counts field: %s, rerank word-counts field: %s",
              rawdocstat.lineno, e.message,
              rd.row.gets_or_else(driver.grid_word_count_field,
                "(missing word-counts field)"),
              rd.row.gets_or_else(driver.rerank_word_count_field,
                "(missing rerank word-counts field)"))
          (None, "bad", "error creating document language model", "")
        }
      }
    }
  }

  /* Record a training document in the factory and any subfactories,
   * subclasses, etc. Recording in the factory mainly involves computing
   * statistics and such for diagnostic purposes, although the subfactory
   * may record stuff for additional purposes (Wikipedia documents currently
   * do this). This does not record the document in the cell grid; the
   * caller needs to do that if needed.
   */
  def record_training_document_in_factory(stat: DocStatus[(RawDoc, GridDoc[Co])]
    ) {
    stat foreach_all { case (status, (rawdoc, doc)) =>
      val split = rawdoc.row.gets_or_else("split", "unknown")
      val double_tokens = doc.lang_model.grid_lm.num_tokens
      val tokens = double_tokens.toInt
      // Partial counts should not occur in training documents.
      // assert_==(double_tokens, tokens, "#tokens")
      if (status == "skipped") {
        assert(!doc.has_coord)
        num_would_be_training_documents_skipped_because_lacking_coordinates_by_split(split) += 1
        word_tokens_of_would_be_training_documents_skipped_because_lacking_coordinates_by_split(split) += tokens
      } else {
        assert(doc.has_coord)
        num_training_documents_by_split(split) += 1
        word_tokens_of_training_documents_by_split(split) += tokens
        num_training_documents_with_coordinates_by_split(split) += 1
        (word_tokens_of_training_documents_with_coordinates_by_split(split)
          += tokens)
        record_training_document_in_subfactory(doc)
      }
    }
  }

  /**
   * Convert raw documents into document statuses.
   *
   * @param note_globally Whether to add each document's words to the global
   *   backoff statistics.  Normally false, but may be true during
   *   bootstrapping of those statistics.
   * @param skip_no_coord If true, skip documents without a coordinate.
   * @param finish_globally Whether to compute global backoff statistics.
   *   Normally true, but may be false during bootstrapping of those
   *   statistics.
   * @return Iterator over document statuses.
   */
  def raw_documents_to_document_statuses(
    rawdocs: Iterator[DocStatus[RawDoc]],
    skip_no_coord: Boolean,
    note_globally: Boolean,
    finish_globally: Boolean
  ): Iterator[DocStatus[(RawDoc, GridDoc[Co])]] = {
    val docstats =
      rawdocs map { rawdoc =>
        raw_document_to_document_status(rawdoc, skip_no_coord, note_globally)
      }

    // NOTE!!! We *cannot* rewrite the following to use `foreach` instead of
    // `map` on `docstats`, and then return `docstats`; this is
    // because doing this will exhaust the iterator.
    if (!finish_globally)
      docstats
    else
      docstats.map { stat =>
        stat.foreach {
          case (rawdoc, doc) => doc.lang_model.finish_after_global() }
        stat
      }
  }

  /**
   * Process the document status objects encapsulating document objects
   * (class `GridDoc`) and extract the document objects themselves.
   * The processing mostly involves logging successes, failures and
   * such to counters (see `DocCounterTracker`). The connection to an
   * external logging mechanism is handled by `driver`, a class parameter
   * of `GridDocFactory` (the class we are within).
   *
   * @param docstatus Iterator over processed documents encapsulated in
   *   `DocStatus` objects. See `raw_documents_to_documents`.
   * @return Iterator directly over processed documents.
   */
  def document_statuses_to_documents(
    rddocstats: Iterator[DocStatus[(RawDoc, GridDoc[Co])]]
  ): Iterator[GridDoc[Co]] = {
    val docstats =
      rddocstats map { _ map_all { case (raw, cooked) => cooked } }
    new DocCounterTrackerFactory[GridDoc[Co]](driver).
      process_statuses(docstats)
  }

  /**
   * Convert raw documents (directly describing the fields of the document,
   * as read from a textdb database or similar) to actual document objects.
   * Both the raw and processed document objects are encapsulated in a
   * `DocStatus` object indicating whether the processing that generated the
   * object was successful or not, and if not, why not. Eventually, the
   * processed document objects themselves are extracted (using
   * `document_statuses_to_documents`); this throws away failures, but in the
   * process logs them appropriately. By encapsulating the success or failure
   * this way, we allow the logging to be delayed until the point where a
   * given document is actually consumed, for proper sequencing.
   *
   * FIXME: This and many other functions are polluted by extra arguments
   * `note_globally` and `finish_globally`. Properly, the extra actions
   * triggered by these arguments should be handled externally, i.e. by
   * separate functions called as necessary by one of the outermost callers.
   * There used to be a third such argument `record_in_factory` (sometimes
   * `record_in_subfactory`), enabled only when processing training
   * documens; now the recording code has been extracted into separate
   * functions, which are called only in the Cell method
   * `default_add_training_documents_to_grid`. Some approach like this
   * should ideally be used for these remaining extra arguments.
   *
   * @param rawdoc Raw document as directly read from a corpus.
   * @param skip_no_coord If true, skip documents without a coordinate.
   * @param note_globally Whether to add each document's words to the global
   *   backoff statistics.  Normally false, but may be true during
   *   bootstrapping of those statistics.
   * @param finish_globally Whether to compute global backoff statistics.
   *   Normally true, but may be false during bootstrapping of those
   *   statistics.
   * @return Iterator over documents.
   */
  def raw_documents_to_documents(
    rawdocs: Iterator[DocStatus[RawDoc]],
    skip_no_coord: Boolean,
    note_globally: Boolean = false,
    finish_globally: Boolean = true
  ) = {
    val docstats = raw_documents_to_document_statuses(rawdocs,
      skip_no_coord, note_globally, finish_globally)
    document_statuses_to_documents(docstats)
  }

  /**
   * Read the documents from a textdb corpus.
   *
   * @param filehand The FileHandler for the directory of the corpus.
   * @param dir Directory containing the corpus.
   * @param suffix Suffix specifying a particular corpus if many exist in
   *   the same dir (e.g. "-dev")
   * @param skip_no_coord If true, skip documents without a coordinate.
   * @param note_globally Whether to add each document's words to the global
   *   backoff statistics.  Normally false, but may be true during
   *   bootstrapping of those statistics.
   * @param finish_globally Whether to compute global backoff statistics.
   *   Normally true, but may be false during bootstrapping of those
   *   statistics.
   * @return Iterator over document statuses.
   */
  def read_document_statuses_from_textdb(filehand: FileHandler, dir: String,
      suffix: String, domain: String, importance: Double,
      skip_no_coord: Boolean, note_globally: Boolean = false,
      finish_globally: Boolean = true) = {
    val rawdocs =
      GridDocFactory.read_raw_documents_from_textdb(filehand, dir, suffix,
        domain, importance, with_messages = driver.params.verbose)
    raw_documents_to_document_statuses(rawdocs, skip_no_coord,
      note_globally, finish_globally)
  }

  /**
   * Read the text from one or more raw files, with each file treated as
   * a document.
   *
   * @param filehand The FileHandler for the directory containing the files.
   * @param dir File or directory containing the files.
   * @param note_globally Whether to add each document's words to the global
   *   backoff statistics.  Normally false, but may be true during
   *   bootstrapping of those statistics.
   * @param finish_globally Whether to compute global backoff statistics.
   *   Normally true, but may be false during bootstrapping of those
   *   statistics.
   * @return Iterator over document statuses.
   */
  def read_document_statuses_from_raw_text(filehand: FileHandler, dir: String,
      domain: String, importance: Double, note_globally: Boolean = false,
      finish_globally: Boolean = true) = {
    val schema = new Schema(Iterable(), Map("corpus-type" -> "raw-text"))
    val fake_rawdoc = RawDoc(Row(schema, IndexedSeq()), domain, importance)
    val the_files = iter_files_recursively(filehand, Iterable(dir))
    val files =
      if (driver.params.verbose)
        iter_files_with_message(filehand, the_files)
      else
        the_files
    files.map { file =>
      val lines = filehand.openr(file)
      val words = lines.flatMap(_.split("""\s+""")).toIterable
      if (words.size == 0) {
        warning(s"Skipping empty file $file")
        DocStatus[(RawDoc, GridDoc[Co])](filehand, file, 1, None, "skipped",
          "empty document", "")
      } else {
        val grid_lm =
          lang_model_factory.grid_lang_model_factory.create_lang_model
        grid_lm.add_document(words, domain, importance)
        val rerank_lm =
          if (driver.rerank_word_count_field == driver.grid_word_count_field)
            grid_lm
          else {
            val lm =
              lang_model_factory.rerank_lang_model_factory.create_lang_model
            lm.add_document(words, domain, importance)
            lm
          }
        val lang_model = new DocLangModel(grid_lm, rerank_lm)
        // FIXME!! Record statistics about total types/tokens/etc.
        val doc = create_document(fake_rawdoc, lang_model)
        if (note_globally) {
          doc.lang_model.foreach(lm => lm.factory.note_lang_model_globally(lm))
        }
        doc.lang_model.finish_before_global()
        if (finish_globally)
          doc.lang_model.finish_after_global()
        DocStatus(filehand, file, 1, Some((fake_rawdoc, doc)), "processed",
          "", "")
      }
    }
  }

  def finish_document_loading() {
    if (debug("docstats"))
      output_document_statistics()
  }

  def output_document_statistics() {
    // Now output statistics on number of documents seen, etc.
    errprint("")
    errprint("-------------------------------------------------------------------------")
    errprint("Document/record/word token statistics:")

    var total_num_records = 0L
    var total_num_error_skipped_records = 0L
    var total_num_documents = 0L
    var total_num_documents_skipped_because_lacking_coordinates = 0L
    var total_num_would_be_training_documents_skipped_because_lacking_coordinates = 0L
    var total_num_training_documents = 0L
    var total_num_documents_with_coordinates = 0L
    var total_num_training_documents_with_coordinates = 0L
    var total_word_tokens_of_documents = 0.0
    var total_word_tokens_of_documents_skipped_because_lacking_coordinates = 0.0
    var total_word_tokens_of_would_be_training_documents_skipped_because_lacking_coordinates = 0.0
    var total_word_tokens_of_training_documents = 0.0
    var total_word_tokens_of_documents_with_coordinates = 0.0
    var total_word_tokens_of_training_documents_with_coordinates = 0.0
    for (split <- num_records_by_split.keys) {
      errprint("For split '%s':", split)

      val num_records = num_records_by_split(split).longValue
      errprint("  %s records seen", num_records)
      total_num_records += num_records

      val num_error_skipped_records =
        num_error_skipped_records_by_split(split).longValue
      errprint("  %s records skipped due to error seen",
        num_error_skipped_records)
      total_num_error_skipped_records += num_error_skipped_records

      def print_line(documents: String, num_documents: Long,
          num_tokens: Double) {
        errprint("  %s %s, %s total tokens, %.2f tokens/document",
          num_documents, documents, num_tokens,
          // Avoid division by zero
          num_tokens / (num_documents + 1e-100))
      }

      val num_documents = num_documents_by_split(split).longValue
      val word_tokens_of_documents =
        word_tokens_of_documents_by_split(split).value
      print_line("documents", num_documents, word_tokens_of_documents)
      total_num_documents += num_documents
      total_word_tokens_of_documents += word_tokens_of_documents

      val num_training_documents =
        num_training_documents_by_split(split).longValue
      val word_tokens_of_training_documents =
        word_tokens_of_training_documents_by_split(split).value
      print_line("training documents", num_training_documents,
        word_tokens_of_training_documents)
      total_num_training_documents += num_training_documents
      total_word_tokens_of_training_documents +=
        word_tokens_of_training_documents

      val num_documents_skipped_because_lacking_coordinates =
        num_documents_skipped_because_lacking_coordinates_by_split(split).longValue
      val word_tokens_of_documents_skipped_because_lacking_coordinates =
        word_tokens_of_documents_skipped_because_lacking_coordinates_by_split(
          split).value
      print_line("documents skipped because lacking coordinates",
        num_documents_skipped_because_lacking_coordinates,
        word_tokens_of_documents_skipped_because_lacking_coordinates)
      total_num_documents_skipped_because_lacking_coordinates +=
        num_documents_skipped_because_lacking_coordinates
      total_word_tokens_of_documents_skipped_because_lacking_coordinates +=
        word_tokens_of_documents_skipped_because_lacking_coordinates

      val num_would_be_training_documents_skipped_because_lacking_coordinates =
        num_would_be_training_documents_skipped_because_lacking_coordinates_by_split(
        split).longValue
      val word_tokens_of_would_be_training_documents_skipped_because_lacking_coordinates =
        word_tokens_of_would_be_training_documents_skipped_because_lacking_coordinates_by_split(
          split).value
      print_line("would-be training documents skipped because lacking coordinates",
        num_would_be_training_documents_skipped_because_lacking_coordinates,
        word_tokens_of_would_be_training_documents_skipped_because_lacking_coordinates)
      total_num_would_be_training_documents_skipped_because_lacking_coordinates +=
        num_would_be_training_documents_skipped_because_lacking_coordinates
      total_word_tokens_of_would_be_training_documents_skipped_because_lacking_coordinates +=
        word_tokens_of_would_be_training_documents_skipped_because_lacking_coordinates

      val num_documents_with_coordinates =
        num_documents_with_coordinates_by_split(split).longValue
      val word_tokens_of_documents_with_coordinates =
        word_tokens_of_documents_with_coordinates_by_split(split).value
      print_line("documents having coordinates",
        num_documents_with_coordinates,
        word_tokens_of_documents_with_coordinates)
      total_num_documents_with_coordinates += num_documents_with_coordinates
      total_word_tokens_of_documents_with_coordinates +=
        word_tokens_of_documents_with_coordinates

      val num_training_documents_with_coordinates =
        num_training_documents_with_coordinates_by_split(split).longValue
      val word_tokens_of_training_documents_with_coordinates =
        word_tokens_of_training_documents_with_coordinates_by_split(split).value
      print_line("training documents having coordinates",
        num_training_documents_with_coordinates,
        word_tokens_of_training_documents_with_coordinates)
      total_num_training_documents_with_coordinates +=
        num_training_documents_with_coordinates
      total_word_tokens_of_training_documents_with_coordinates +=
        word_tokens_of_training_documents_with_coordinates
    }

    errprint("Total: %s records, %s skipped records from error",
      total_num_records,
      total_num_error_skipped_records)
    errprint("Total: %s documents with %s total word tokens",
      total_num_documents, total_word_tokens_of_documents)
    errprint("Total: %s documents skipped because lacking coordinates,\n       with %s total word tokens",
      total_num_documents_skipped_because_lacking_coordinates,
      total_word_tokens_of_documents_skipped_because_lacking_coordinates)
    errprint("Total: %s would-be training documents skipped because lacking coordinates,\n       with %s total word tokens",
      total_num_would_be_training_documents_skipped_because_lacking_coordinates,
      total_word_tokens_of_would_be_training_documents_skipped_because_lacking_coordinates)
    errprint("Total: %s training documents with %s total word tokens",
      total_num_training_documents, total_word_tokens_of_training_documents)
    errprint("Total: %s documents having coordinates with %s total word tokens",
      total_num_documents_with_coordinates,
      total_word_tokens_of_documents_with_coordinates)
    errprint("Total: %s training documents having coordinates with %s total word tokens",
      total_num_training_documents_with_coordinates,
      total_word_tokens_of_training_documents_with_coordinates)
  }
}

object GridDocFactory {
  /**
   * Convert a line from a textdb database into a raw document status,
   * listing the fields in the document.
   *
   * @param schema Schema for textdb, indicating names of fields, etc.
   */
  def line_to_raw_document(filehand: FileHandler, file: String, line: String,
      lineno: Long, domain: String, importance: Double, schema: Schema
    ): DocStatus[RawDoc] = {
    line_to_fields(line, lineno, schema) match {
      case Some(fields) =>
        DocStatus(filehand, file, lineno,
          Some(RawDoc(Row(schema, fields), domain, importance)),
          "processed", "", "")
      case None =>
        DocStatus(filehand, file, lineno, None, "bad",
          "badly formatted database row", "")
    }
  }

  /**
   * Read the raw documents from a textdb corpus.
   *
   * @param filehand The FileHandler for the directory of the corpus.
   * @param dir Directory containing the corpus.
   * @param suffix Suffix specifying a particular corpus if many exist in
   *   the same dir (e.g. "-dev")
   * @param with_messages If true, display messages about files being loaded.
   * @return Iterator over document statuses of raw documents.
   */
  def read_raw_documents_from_textdb(filehand: FileHandler, dir: String,
      suffix: String, domain: String, importance: Double,
      with_messages: Boolean = false
  ): Iterator[DocStatus[RawDoc]] = {
    val (schema, files) =
      TextDB.get_textdb_files(filehand, dir, suffix_re = suffix,
        with_messages = with_messages)
    files.flatMap { file =>
      filehand.openr(file).zipWithIndex.map {
        case (line, idx) =>
          line_to_raw_document(filehand, file, line, idx + 1, domain,
            importance, schema)
      }
    }
  }
}

/////////////////////////////////////////////////////////////////////////////
//                                GridDocs                                  //
/////////////////////////////////////////////////////////////////////////////

/**
 * An exception thrown to indicate an error during document creation
 * (typically due to a bad field value).
 */
case class DocValidationException(
  message: String,
  cause: Option[Throwable] = None
) extends RethrowableRuntimeException(message)

/**
 * A document with an associated coordinate placing it in a grid, with a
 * language model describing the text of the document.  For a "coordinate"
 * referring to a location on the Earth, documents can come from Wikipedia
 * articles, individual tweets, Twitter feeds (all tweets from a user), book
 * chapters from travel stories, etc.  For a "coordinate" referring to a
 * point in time, documents might be biographical entries in an encyclopedia,
 * snippets of text surrounding a date from an arbitrary web source, etc.
 *
 * The fields available depend on the source of the document (e.g. Wikipedia,
 * Twitter, etc.).
 *
 * Defined general fields:
 *
 * corpus: Corpus of the document (stored as a fixed field in the schema).
 * corpus-type: Corpus type of the corpus ('wikipedia', 'twitter', stored
 *   as a fixed field in the schema.
 * title: Title of document.  The combination of (corpus, title) needs to
 *   uniquely identify a document.
 * coord: Coordinates of document.
 * split: Evaluation split of document ("training", "dev", "test"), usually
 *   stored as a fixed field in the schema.
 *
 * @param schema
 * @param lang_model_factory
 * @param lang_model Object containing language model of this document.
 */
abstract class GridDoc[Co : TextSerializer : CoordHandler](
  val schema: Schema,
  val lang_model: DocLangModel
) {

  import util.serialize.TextSerializer._

  /**
   * Title of the document -- something that uniquely identifies it,
   * at least within all documents with a given corpus name. (The combination
   * of corpus and title uniquely identifies the document.) Title can be
   * taken from an actual title (e.g. in Wikipedia) or ID of some sort
   * (e.g. a tweet ID), or simply taken from an assigned serial number.
   * The underlying representation can be arbitrary -- `title` is just a
   * function to produce a string representation.
   *
   * FIXME: The corpus name is stored in the schema, but we don't currently
   * make any attempt to verify that corpus names are unique or implement
   * any operations involving corpus names -- much less verify that titles
   * are in fact unique for a given corpus name.  There's in general no way
   * to look up a document by title -- perhaps this is good since such
   * lookup adds a big hash table and entails storing the documents to
   * begin with, both of which things we want to avoid when possible.
   */
  def title: String
  /**
   * True if this document has a coordinate associated with it.
   */
  def has_coord: Boolean
  /**
   * Return the coordinate of a document with a coordinate.  Results are
   * undefined if the document has no coordinate (e.g. it might throw an
   * error or return a default value such as `null`).
   */
  def coord: Co
  /**
   * Return the evaluation split ("training", "dev" or "test") of the document.
   * This was created before corpora were sub-divided by the value of this
   * field, and hence it could be a property of the document.  It's now a
   * fixed value in the schema, but the field remains.
   */
  def split = schema.get_fixed_field("split")
  /**
   * Return the salience value associated with a document, if it exists;
   * else return None. This value is intended to provide a relative
   * measure of a document's salience, for use e.g. as a prior for the Naive
   * Bayes raner. This might be, for example, the population of the city or
   * region associated with a document or (e.g. for Wikipedia articles) the
   * number of incoming links pointing to the article from other articles.
   *
   * The salience value is assumed to be additive, i.e. the combined
   * salience value for a cell is computed by adding up the individual
   * salience values of the documents in the cell.
   *
   * FIXME: Perhaps we want to split it into has_salience and salience
   * (without the Option[] wrapping). We also might need some concept of
   * "corpus type" and a way to request whether a given corpus type has
   * salience values marked on it.
   */
  def salience: Option[Double] = None

  val grid_lm = lang_model.grid_lm
  val rerank_lm = lang_model.rerank_lm

  def get_fields(fields: Iterable[String]) = fields map get_field

  /**
   * Return the value of the given field.
   */
  def get_field(field: String) = {
    field match {
      case "title" => title
      case "coord" => if (has_coord) put_x(coord) else ""
      case _ => internal_error(s"Unrecognized field name $field")
    }
  }

  override def toString = {
    val coordstr = if (has_coord) s" at $coord" else ""
    val corpus_name = schema.get_fixed_field_or_else("corpus-name", null)
    val corpusstr =
      if (corpus_name != null) s" (corpus $corpus_name)" else ""
    s"$title$coordstr$corpusstr"
  }

  def coord_handler = implicitly[CoordHandler[Co]]

  /**
   * Return distance between two coordinates.
   */
  def distance_between_coords(c1: Co, c2: Co) =
    coord_handler.distance_between_coords(c1, c2)

  /**
   * Format a coordinate for human-readable display.
   */
  def format_coord(coord2: Co) =
    coord_handler.format_coord(coord2)

  /**
   * Output a distance with attached units
   */
  def output_distance(dist: Double) =
    coord_handler.output_distance(dist)

  def distance_to_coord(coord2: Co) = {
    assert(has_coord)
    distance_between_coords(coord, coord2)
  }

  def distance_to_cell(cell: GridCell[Co]) = distance_to_coord(cell.get_central_point)
}
