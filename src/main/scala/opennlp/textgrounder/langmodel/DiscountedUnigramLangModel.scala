///////////////////////////////////////////////////////////////////////////////
//  DiscountedUnigramLangModel.scala
//
//  Copyright (C) 2010-2014 Ben Wing, The University of Texas at Austin
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
package langmodel

import math._

import util.collection._
import util.print.errprint

import util.debug._

abstract class DiscountedUnigramLangModelFactory(
  create_builder: LangModelFactory => LangModelBuilder,
  val interpolate: Boolean,
  val tf_idf: Boolean,
  val normlm: Boolean
) extends UnigramLangModelFactory {
  val builder = create_builder(this)

  // Estimate of number of unseen word types for all documents
  var total_num_unseen_word_types = 0

  /**
   * Overall probabilities over all documents of seeing a word in a document,
   * for all words seen at least once in any document, computed using the
   * empirical frequency of a word among all documents, adjusted by the mass
   * to be assigned to globally unseen words (words never seen at all), i.e.
   * the value in 'globally_unseen_word_prob'.  We start out by storing raw
   * counts, then adjusting them.
   */
  var overall_word_probs = Unigram.create_gram_double_map
  var owp_adjusted = false
  var document_freq = Unigram.create_gram_double_map
  var num_documents = 0
  var global_normalization_factor = 0.0

  override def note_lang_model_globally(lm: LangModel) {
    super.note_lang_model_globally(lm)
    assert(!owp_adjusted)
    for ((word, count) <- lm.iter_grams) {
      if (!(overall_word_probs contains word))
        total_num_word_types += 1
      // Record in overall_word_probs; note more tokens seen.
      overall_word_probs(word) += count
      total_num_word_tokens += count
      // Note document frequency of word
      document_freq(word) += 1
    }
    num_documents += 1
    //if (debug("lang-model")) {
    //  val ulm = lm.asInstanceOf[DiscountedUnigramLangModel]
    //  errprint("""For lang model, total tokens = %s, unseen_mass = %s, overall unseen mass = %s""",
    //    ulm.num_tokens, ulm.unseen_mass, ulm.overall_unseen_mass)
    //}
  }

  // The total probability mass to be assigned to words not seen at all in
  // any document, estimated using Good-Turing smoothing as the unadjusted
  // empirical probability of having seen a word once.  No longer used at
  // all in the "new way".
  // var globally_unseen_word_prob = 0.0

  // For documents whose word counts are not known, use an empty list to
  // look up in.
  // unknown_document_counts = ([], [])

  def finish_global_backoff_stats() {
    /* We do in-place conversion of counts to probabilities.  Make sure
       this isn't done twice!! */
    assert (!owp_adjusted)
    owp_adjusted = true
    // A holdout from the "old way".
    val globally_unseen_word_prob = 0.0
    if (tf_idf) {
      for ((word, count) <- overall_word_probs)
        overall_word_probs(word) =
          count*math.log(num_documents/document_freq(word))
    }
    global_normalization_factor = ((overall_word_probs.values) sum)
    for ((word, count) <- overall_word_probs)
      overall_word_probs(word) = (
        count.toDouble/global_normalization_factor*(1.0 - globally_unseen_word_prob))
  }
}

abstract class DiscountedUnigramLangModel(
  override val factory: DiscountedUnigramLangModelFactory
) extends UnigramLangModel(factory) {
  type TThis = DiscountedUnigramLangModel
  type TKLCache = DiscountedUnigramKLDivergenceCache

  /** Total probability mass to be assigned to all words not
      seen in the document.  This indicates how much mass to "discount" from
      the unsmoothed (maximum-likelihood) estimated language model of the
      document.  This can be document-specific and is one of the two basic
      differences between the smoothing methods investigated here:

      1. Jelinek-Mercer uses a constant value.
      2. Dirichlet uses a value that is related to document length, getting
         smaller as document length increases.
      3. Pseudo Good-Turing, motivated by Good-Turing smoothing, computes this
         mass as the unadjusted empirical probability of having seen a word
         once.

      The other difference is whether to do interpolation or back-off.
      This only affects words that exist in the unsmoothed model.  With
      interpolation, we mix the unsmoothed and global models, using the
      discount value.  With back-off, we only use the unsmoothed model in
      such a case, using the global model only for words unseen in the
      unsmoothed model.

      In other words:

      1. With interpolation, we compute the probability as

          COUNTS[W]/TOTAL_TOKENS*(1 - UNSEEN_MASS) +
            UNSEEN_MASS * overall_word_probs[W]

         For unseen words, only the second term is non-zero.

      2. With back-off, for words with non-zero MLE counts, we compute
         the probability as

          COUNTS[W]/TOTAL_TOKENS*(1 - UNSEEN_MASS)

         For other words, we compute the probability as

        UNSEEN_MASS * (overall_word_probs[W] / OVERALL_UNSEEN_MASS)

        The idea is that overall_word_probs[W] / OVERALL_UNSEEN_MASS is
        an estimate of p(W | W not in A).  We have to divide by
        OVERALL_UNSEEN_MASS to make these probabilities be normalized
        properly.  We scale p(W | W not in A) by the total probability mass
        we have available for all words not seen in A.

      3. An additional possibility is that we are asked the probability of
         a word never seen at all.  The old code I wrote tried to assign
         a non-zero probability to such words using the formula

        UNSEEN_MASS * globally_unseen_word_prob / NUM_UNSEEN_WORDS

        where NUM_UNSEEN_WORDS is an estimate of the total number of words
        "exist" but haven't been seen in any documents.  Based on Good-Turing
        motivation, we used the number of words seen once in any document.
        This certainly underestimates this number if not too many documents
        have been seen but might be OK if many documents seen.

        The paper on this subject suggests assigning zero probability to
        such words and ignoring them entirely in calculations if/when they
        occur in a query.
   */
  var unseen_mass = 0.5
  /**
     Probability mass assigned in 'overall_word_probs' to all words not seen
     in the document.  This is 1 - (sum over W in A of overall_word_probs[W]).
     See above.
   */
  var overall_unseen_mass = 1.0

  override def innerToString = ", %.2f unseen mass" format unseen_mass

  var normalization_factor = 0.0

  /**
   * Here we compute the value of `overall_unseen_mass`, which depends
   * on the global `overall_word_probs` computed from all of the
   * lang models.
   */
  protected def imp_finish_after_global() {
    // Make sure that overall_word_probs has been computed properly.
    assert(factory.owp_adjusted)

    if (factory.interpolate)
      overall_unseen_mass = 1.0
    else
      overall_unseen_mass = 1.0 - (
        (for (ind <- iter_keys)
          yield factory.overall_word_probs(ind)) sum)
    if (factory.tf_idf) {
      for ((word, count) <- iter_grams_for_modify) {
        /* The classic formula doesn't have the +1 in it. But if we do this
         * transformation on a test-set doc with a word unseen in the training
         * set, the document freq will be 0 and the inside factor will be
         * infinity. This may make the IDF less than 0; we check for this. */
        var idf = log(factory.num_documents/(factory.document_freq(word) + 1))
        /* IDF may be <= 0 for the reason given above; without the +1 it might
         * still be 0. This can mess up our counts, so hack it to a small
         * positive number.
         *
         * FIXME: We really don't want to be directly modifying the counts like
         * this, but instead have a transformation function that's applied
         * appropriately in certain circumstances (e.g. during cosine similarity
         * or sum-frequency). The sum-frequency is probably messed up by this
         * since it may mess up the denominator of the TF part.
         *
         * OTOH, modifying the counts like this is exactly what we want when
         * using gram-doc-count using VW.
         */
        if (idf <= 0)
          idf = 0.0001
        set_gram(word, count*idf)
      }
    }

    // As for tf-idf, we directly modify the counts, which we do mostly for
    // gram-doc-count using VW.
    if (factory.normlm) {
      var sumsq = 0.0
      for ((word, count) <- iter_grams)
        sumsq += count * count
      sumsq = 1.0 / math.sqrt(sumsq)
      for ((word, count) <- iter_grams_for_modify) {
        set_gram(word, count*sumsq)
      }
    }

    normalization_factor = num_tokens
    // assert(normalization_factor > 0,
    //  "Zero normalization factor for lm %s" format this)
    if (normalization_factor == 0)
      normalization_factor = 1
    //if (LangModelConstants.use_sorted_list)
    //  counts = new SortedList(counts)
    //if (debug("discount-factor") || debug("discountfactor"))
    //  errprint("For lang model %s, norm_factor = %g, model.num_tokens = %s, unseen_mass = %g"
    //    format (this, normalization_factor, num_tokens, unseen_mass))
  }

  def fast_kl_divergence(other: LangModel, partial: Boolean = true,
      cache: KLDivergenceCache = null) = {
    FastDiscountedUnigramLangModel.fast_kl_divergence(
      this.asInstanceOf[TThis], cache.asInstanceOf[TKLCache],
      other.asInstanceOf[TThis], interpolate = factory.interpolate,
      partial = partial)
  }

  def cosine_similarity(other: LangModel, partial: Boolean = true,
      smoothed: Boolean = false) = {
    if (smoothed)
      FastDiscountedUnigramLangModel.fast_smoothed_cosine_similarity(
        this.asInstanceOf[TThis], other.asInstanceOf[TThis],
        partial = partial)
    else
      FastDiscountedUnigramLangModel.fast_cosine_similarity(
        this.asInstanceOf[TThis], other.asInstanceOf[TThis],
        partial = partial)
  }

  def kl_divergence_34(other: UnigramLangModel) = {
    var overall_probs_diff_words = 0.0
    for (word <- other.iter_keys if !contains(word)) {
      overall_probs_diff_words += factory.overall_word_probs(word)
    }

    inner_kl_divergence_34(other.asInstanceOf[TThis],
      overall_probs_diff_words)
  }

  /**
   * Actual implementation of steps 3 and 4 of KL-divergence computation, given
   * a value that we may want to compute as part of step 2.
   */
  def inner_kl_divergence_34(other: TThis,
      overall_probs_diff_words: Double) = {
    var kldiv = 0.0

    // 3. For words seen in neither lm but seen globally:
    // You can show that this is
    //
    // factor1 = (log(self.unseen_mass) - log(self.overall_unseen_mass)) -
    //           (log(other.unseen_mass) - log(other.overall_unseen_mass))
    // factor2 = self.unseen_mass / self.overall_unseen_mass * factor1
    // kldiv = factor2 * (sum(words seen globally but not in either lm)
    //                    of overall_word_probs[word])
    //
    // The final sum
    //   = 1 - sum(words in self) overall_word_probs[word]
    //       - sum(words in other, not self) overall_word_probs[word]
    //   = self.overall_unseen_mass
    //       - sum(words in other, not self) overall_word_probs[word]
    //
    // So we just need the sum over the words in other, not self.
    //
    // Note that the above formula was derived using back-off, but it
    // still applies in interpolation.  For words seen in neither lm,
    // the only difference between back-off and interpolation is that
    // the "overall_unseen_mass" factors for all lang models are
    // effectively 1.0 (and the corresponding log terms above disappear).

    val factor1 = ((log(unseen_mass) - log(overall_unseen_mass)) -
               (log(other.unseen_mass) - log(other.overall_unseen_mass)))
    val factor2 = unseen_mass / overall_unseen_mass * factor1
    val the_sum = overall_unseen_mass - overall_probs_diff_words
    kldiv += factor2 * the_sum

    // 4. For words never seen at all:
    /* The new way ignores these words entirely.
    val p = (unseen_mass*factory.globally_unseen_word_prob /
          factory.total_num_unseen_word_types)
    val q = (other.unseen_mass*factory.globally_unseen_word_prob /
          factory.total_num_unseen_word_types)
    kldiv += factory.total_num_unseen_word_types*(p*(log(p) - log(q)))
    */
    kldiv
  }

  protected def imp_gram_prob(word: Gram) = {
    if (factory.interpolate) {
      val wordcount = if (contains(word)) get_gram(word) else 0.0
      // if (debug("lang-model")) {
      //   errprint("Found counts for document %s, num word types = %s",
      //            doc, wordcounts(0).length)
      //   errprint("Unknown prob = %s, overall_unseen_mass = %s",
      //            unseen_mass, overall_unseen_mass)
      // }
      val owprob = factory.overall_word_probs.getOrElse(word, 0.0)
      val mle_wordprob = wordcount.toDouble/normalization_factor
      val wordprob = mle_wordprob*(1.0 - unseen_mass) + owprob*unseen_mass
      //if (debug("lang-model"))
      //  errprint("Word %s, seen in document, wordprob = %s",
      //           gram_to_string(word), wordprob)
      // DO NOT simplify following expr, or it will fail on NaN
      if (! (wordprob >= 0)) {
        errprint("wordcount = %s, owprob = %s", wordcount, owprob)
        errprint("mle_wordprob = %s, normalization_factor = %s, unseen_mass = %s",
          mle_wordprob, normalization_factor, unseen_mass)
      }
      // Info on word and probability printed in wrapper gram_prob()
      // for bad probability, and assert(false) occurs there
      wordprob
    } else {
      val retval =
        if (!contains(word)) {
          factory.overall_word_probs.get(word) match {
            case None => {
              /*
              The old way:

              val wordprob = (unseen_mass*factory.globally_unseen_word_prob
                        / factory.total_num_unseen_word_types)
              */
              /* The new way: Just return 0 */
              val wordprob = 0.0
              //if (debug("lang-model"))
              //  errprint("Word %s, never seen at all, wordprob = %s",
              //           gram_to_string(word), wordprob)
              wordprob
            }
            case Some(owprob) => {
              val wordprob = unseen_mass * owprob / overall_unseen_mass
              // DO NOT simplify following expr, or it will fail on NaN
              if (! (wordprob >= 0)) {
                errprint("Bad values section #2; unseen_mass = %s, owprob = %s, overall_unseen_mass = %s",
                  unseen_mass, owprob, overall_unseen_mass)
              }
              //if (debug("lang-model"))
              //  errprint("Word %s, seen but not in document, wordprob = %s",
              //           gram_to_string(word), wordprob)

              wordprob
            }
          }
        } else {
          val wordcount = get_gram(word)
          //if (wordcount <= 0 or num_tokens <= 0 or unseen_mass >= 1.0)
          //  warning("Bad values; wordcount = %s, unseen_mass = %s",
          //          wordcount, unseen_mass)
          //  for ((word, count) <- self.counts)
          //    errprint("%s: %s", word, count)
          val wordprob = wordcount.toDouble/normalization_factor*(1.0 - unseen_mass)
          // DO NOT simplify following expr, or it will fail on NaN
          if (! (wordprob >= 0)) {
            errprint("Bad values section #3; wordcount = %s, normalization_factor = %s, unseen_mass = %s",
              wordcount, normalization_factor, unseen_mass)
          }
          //if (debug("lang-model"))
          //  errprint("Word %s, seen in document, wordprob = %s",
          //           gram_to_string(word), wordprob)
          wordprob
        }
      // Info on word and probability printed in wrapper gram_prob()
      // for bad probability, and assert(false) occurs there
      retval
    }
  }
}

