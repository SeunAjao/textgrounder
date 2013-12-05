///////////////////////////////////////////////////////////////////////////////
//  CellDist.scala
//
//  Copyright (C) 2010-2013 Ben Wing, The University of Texas at Austin
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

import collection.mutable

import util.collection.{LRUCache, doublemap}
import util.print.{errprint, warning}

import langmodel.{Word,LangModel,Unigram,UnigramLangModel}

/**
 * A general distribution over cells, associating a probability with each
 * cell.  The caller needs to provide the probabilities.
 */

class CellDist[Co](
  val grid: Grid[Co]
) {
  val cellprobs = mutable.Map[GridCell[Co], Double]()

  def set_cell_probabilities(
      probs: collection.Map[GridCell[Co], Double]) {
    cellprobs.clear()
    cellprobs ++= probs
  }

  def get_ranked_cells(include: Iterable[GridCell[Co]]) = {
    val probs =
      if (include.size == 0)
        cellprobs
      else
        // Elements on right override those on left
        include.map((_, 0.0)).toMap ++ cellprobs.toMap
    // sort by second element of tuple, in reverse order
    probs.toIndexedSeq sortWith (_._2 > _._2)
  }
}

/**
 * Distribution over cells that is associated with a word. This class knows
 * how to populate its own probabilities, based on the relative probabilities
 * of the word in the language models of the various cells.  That is,
 * if we have a set of cells, each with a language model, then we can
 * imagine conceptually inverting the process to generate a cell distribution
 * over words.  Basically, for a given word, look to see what its probability
 * is in all cells; normalize, and we have a cell distribution.
 *
 * Instances of this class are normally generated by a factory, specifically
 * `CellDistFactory` or a subclass.  Currently only used by `SphereWordCellDist`
 * and `SphereCellDistFactory`; see them for info on how they are used.
 *
 * @param word Word for which the cell is computed
 * @param cellprobs Hash table listing probabilities associated with cells
 */

class WordCellDist[Co](
  grid: Grid[Co],
  val word: Word
) extends CellDist[Co](grid) {
  var normalized = false

  protected def init() {
    // It's expensive to compute the value for a given word so we cache
    // language models.
    var totalprob = 0.0
    // Compute and store un-normalized probabilities for all cells
    for (cell <- grid.iter_nonempty_cells) {
      val lang_model =
        Unigram.check_unigram_lang_model(cell.grid_lm)
      val prob = lang_model.lookup_word(word)
      // Another way of handling zero probabilities.
      /// Zero probabilities are just a bad idea.  They lead to all sorts of
      /// pathologies when trying to do things like "normalize".
      //if (prob == 0.0)
      //  prob = 1e-50
      cellprobs(cell) = prob
      totalprob += prob
    }
    // Normalize the probabilities; but if all probabilities are 0, then
    // we can't normalize, so leave as-is. (FIXME When can this happen?
    // It does happen when you use --mode=generate-kml and specify words
    // that aren't seen.  In other circumstances, the smoothing ought to
    // ensure that 0 probabilities don't exist?  Anything else I missed?)
    if (totalprob != 0) {
      normalized = true
      for ((cell, prob) <- cellprobs)
        cellprobs(cell) /= totalprob
    } else
      normalized = false
  }

  init()
}

/**
 * Factory object for creating CellDists, i.e. objects describing a
 * distribution over cells.  You can create two types of CellDists, one for
 * a single word and one based on a distribution of words (language model).
 * The former process returns a WordCellDist, which initializes the probability
 * distribution over cells as described for that class.  The latter process
 * returns a basic CellDist.  It works by retrieving WordCellDists for
 * each of the words in the distribution, and then averaging all of these
 * distributions, weighted according to probability of the word in the word
 * distribution.
 *
 * The call to `get_cell_dist` on this class either locates a cached
 * distribution or creates a new one, using `create_word_cell_dist`,
 * which creates the actual `WordCellDist` class.
 *
 * @param lru_cache_size Size of the cache used to avoid creating a new
 *   WordCellDist for a given word when one is already available for that
 *   word.
 */

class CellDistFactory[Co](
  val lru_cache_size: Int
) {
  def create_word_cell_dist(
    grid: Grid[Co], word: Word
  ) = new WordCellDist[Co](grid, word)

  var cached_dists: LRUCache[Word, WordCellDist[Co]] = null

  /**
   * Return a cell distribution over a single word, using a least-recently-used
   * cache to optimize access.
   */
  def get_cell_dist(grid: Grid[Co], word: Word) = {
    if (cached_dists == null)
      cached_dists = new LRUCache(maxsize = lru_cache_size)
    cached_dists.get(word) match {
      case Some(dist) => dist
      case None => {
        val dist = create_word_cell_dist(grid, word)
        cached_dists(word) = dist
        dist
      }
    }
  }

  /**
   * Return a cell distribution over a language model.  This works
   * by adding up the unsmoothed language models of the individual words,
   * weighting by the count of the each word.
   */
  def get_cell_dist_for_lang_model(grid: Grid[Co], xlang_model: LangModel) = {
    // FIXME!!! Figure out what to do if lang model is not a unigram model.
    // Can we break this up into smaller operations?  Or do we have to
    // make it an interface for LangModel?
    val lang_model = xlang_model.asInstanceOf[UnigramLangModel]
    val cellprobs = doublemap[GridCell[Co]]()
    for ((word, count) <- lang_model.model.iter_items) {
      val dist = get_cell_dist(grid, word)
      for ((cell, prob) <- dist.cellprobs)
        cellprobs(cell) += count * prob
    }
    val totalprob = (cellprobs.values sum)
    for ((cell, prob) <- cellprobs)
      cellprobs(cell) /= totalprob
    val retval = new CellDist[Co](grid)
    retval.set_cell_probabilities(cellprobs)
    retval
  }
}

