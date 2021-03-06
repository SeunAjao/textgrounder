///////////////////////////////////////////////////////////////////////////////
//  Classifier.scala
//
//  Copyright (C) 2012-2014 Ben Wing, The University of Texas at Austin
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
package learning

/**
 * Code for machine-learning classification.
 *
 * @author Ben Wing
 */

import util.debug._
import util.error._
import util.io.localfh
import util.math.argmax
import util.metering._
import util.numeric.format_double
import util.print.errprint
import util.verbose._

object ClassifierConstants {
  private val default_weights_to_print = 100
  private val default_min_sig = 50.0

  def weights_to_print = debugint("weights-to-print", default_weights_to_print)
  def min_sig = debugdouble("min-sig", default_min_sig)
}

/**
 * Mix-in for classifiers and classifier trainers.
 *
 * @tparam DI Type of object used to describe a data instance.
 *   For classifiers themselves, this is a `FeatureVector`.  For classifier
 *   trainers, this encapsulates a feature vector and possibly other
 *   application-specific data.
 */
trait ClassifierLike[DI] {
  /** Return number of labels associated with a given instance. (In
    * fixed-depth classifiers, this value is the same for all instances.) */
  def number_of_labels(inst: DI): Int
  def pretty_print_labeled(inst: DI, prefix: String, correct: LabelIndex)
}

trait DataInstance {
  /** Get the feature vector corresponding to a data instance. */
  def feature_vector: FeatureVector
  def pretty_print_labeled(prefix: String, correct: LabelIndex)
}

/**
 * A basic classifier.
 *
 * The code below is written rather generally to support cases where the
 * features in a feature vector may or may not vary depending on the label
 * to be predicted; where there may or may not be a separate weight vector
 * for each label; and where the number of labels to be predicted may or
 * may not vary from instance to instance.
 *
 * A basic distinction is made below between "fixed-depth" classifiers,
 * where the number of labels is fixed for all instances, and "variable-dpeth"
 * classifiers, where different instances may have different numbers of
 * labels.
 *
 * The two most common cases are:
 *
 * 1. A basic fixed-depth classifier. The number of labels is an overall
 *    property of the classifier. The feature vector describing an instance
 *    is a `SimpleFeatureVector`, i.e. it has the same features regardless
 *    of the label (and hence can be used with classifiers supporting any
 *    number of labels).  There is a separate weight vector for each label
 *    (using a `MultiVectorAggregate`) -- this is necessarily the case when
 *    the feature vectors themselves don't vary by label, since either the
 *    weights or features must vary from label to label or the classifier
 *    cannot distinguish one label from another.
 *
 * 2. A basic variable-depth classifier.  Each instance has a particular
 *    number of allowable labels, which may vary from instance to instance.
 *    In such a case, since the number of possible labels isn't known in
 *    advance, there is a single weight vector for all labels (using a
 *    `SingleVectorAggregate`), and correspondingly the features in a given
 *    feature vector must vary from instance to instance (using an
 *    `AggregateFeatureVector`).
 */
trait Classifier[DI] extends ClassifierLike[DI] {
  /** Classify a given instance, returning the label (from 0 to
    * `number_of_labels`-1). */
  def classify(inst: DI): LabelIndex
}

/**
 * A classifier which is capable of returning scores for the different
 * possible labels, rather than just the best label.
 */
trait ScoringClassifier[DI] extends Classifier[DI] {
  /** Score a given instance.  Return a sequence of predicted scores, of
    * the same length as the number of labels present.  There is one score
    * per label, and the maximum score corresponds to the single predicted
    * label if such a prediction is desired. */
  def score(inst: DI): IndexedSeq[Double]

//  /** Score a given instance and generate probabilities of each possible
//    * label, assuming a maxent (exponential) transformation of the scores to
//    * probabilities. Return a sequence of predicted probabilities, of
//    * the same length as the number of labels present, as with `score`.
//    */
//  def maxent_prob(inst: DI): IndexedSeq[Double] = {
//    val exp_scores = score(inst).map { math.exp(_) }
//    val sum = exp_scores.sum
//    exp_scores.map { _/sum }
//  }

  /** Return a sorted sequence of pairs of `(label, score)`, sorted in
    * descending order.  The first item is the best label, hence the label
    * that will be returned by `classify` (except possibly when there are
    * multiple best labels).  The difference between the score of the first
    * and second labels is the margin between the predicted and best
    * non-predicted label, which can be viewed as a confidence measure.
    */
  def sorted_scores(inst: DI) = {
    val scores = score(inst)
    ((0 until scores.size) zip scores).sortWith(_._2 > _._2)
  }
}

/**
 * A scoring classifier for which it's possible to score an individual
 * which is capable of returning scores for the different
 * possible labels, rather than just the best label.
 */
trait IndivScoringClassifier[DI] extends ScoringClassifier[DI] {
  /** Score a given instance for a single label. */
  def score_label(inst: DI, label: LabelIndex): Double

  /** Return the best label. */
  def classify(inst: DI) =
    argmax(0 until number_of_labels(inst)) { score_label(inst, _) }

  /** Score a given instance.  Return a sequence of predicted scores, of
    * the same length as the number of labels present.  There is one score
    * per label, and the maximum score corresponds to the single predicted
    * label if such a prediction is desired. */
  def score(inst: DI): IndexedSeq[Double] =
    (0 until number_of_labels(inst)).map(score_label(inst, _)).toIndexedSeq
}

object LinearClassifier {
  /** Print the weight coefficients with the largest absolute value.
    *
    * @param weights Aggregate of weights to print.
    * @param maybestats Optional feature stats, which needs to contain the
    *   absolute sum of the values of each feature.
    * @param format_feature Function to display feature index as a string.
    * @param num_items Number of items to print. If &lt;= 0, print all items.
    */
  def debug_print_weights(weights: VectorAggregate,
      format_feature: FeatIndex => String,
      maybestats: Option[FeatureStats],
      num_items: Int) {
    errprint("Weights: length=%s, depth=%s, max=%s, min=%s",
       weights.length, weights.depth, weights.max, weights.min)
    for (depth <- 0 until weights.depth) { // Do all components in aggregate
      val rawvec = weights(depth)
      errprint(s"  Raw weights at depth $depth: ")
      val vec = rawvec.toIterable.toIndexedSeq.view

      { // Show the raw weights
        val all_sorted_items =
          vec.zipWithIndex.sortWith(_._1.abs > _._1.abs)
        val sorted_items =
          if (num_items <= 0) all_sorted_items
          else all_sorted_items.take(num_items)
        for (((coeff, feat), index) <- sorted_items.zipWithIndex) {
          errprint("#%s: %s (%s) = %s", index + 1, format_feature(feat),
            feat, coeff)
        }
      }
      if (maybestats != None) {
        errprint(s"  Weights at depth $depth by relative contribution: ")
        val stats = maybestats.get
        val scaled_weights = vec.zipWithIndex.map { case (coeff, feat) =>
          val count = stats.count(feat)
          val absavg =
            if (count == 0) 0.0
            else stats.sum(feat)/count
          if (absavg == 0)
            warning(s"Feature $feat (${format_feature(feat)}) " +
              "has average 0, will be ignored")
          (coeff, absavg, feat)
        }
        val all_sorted_items =
          scaled_weights.sortBy { x => -(x._1*x._2).abs }
        val sorted_items =
          if (num_items <= 0) all_sorted_items
          else all_sorted_items.take(num_items)
        for (((coeff, absavg, feat), index) <- sorted_items.zipWithIndex) {
          errprint("#%s: %s (%s) = %s*%s = %s", index + 1, format_feature(feat),
            feat, coeff, absavg, coeff*absavg)
        }
      }
    }
  }

  def debug_print_weights(weights: VectorAggregate,
      format_feature: FeatIndex => String,
      maybe_stats: Option[FeatureStats] = None) {
    debug_print_weights(weights, format_feature, maybe_stats,
      ClassifierConstants.weights_to_print)
  }
}

trait LinearClassifierLike[DI <: DataInstance] extends ClassifierLike[DI] {
  def pretty_print_labeled(inst: DI, prefix: String,
    correct: LabelIndex) = inst.pretty_print_labeled(prefix, correct)
}

abstract class LinearClassifier(
  val weights: VectorAggregate
) extends IndivScoringClassifier[FeatureVector]
    with LinearClassifierLike[FeatureVector] {
  def score_label(inst: FeatureVector, label: LabelIndex) =
    inst.dot_product(weights(label), label)
}

/** Mix-in for a fixed-depth classifier (or trainer thereof). */
trait FixedDepthClassifierLike[DI]
    extends ClassifierLike[DI] {
  val num_labels: Int
  def number_of_labels(inst: DI) = num_labels
}

/** Mix-in for a variable-depth classifier (or trainer thereof). */
trait VariableDepthClassifierLike[DI]
    extends ClassifierLike[DI] {
}

/** Mix-in for a binary classifier (or trainer thereof). */
trait BinaryClassifierLike[DI]
    extends FixedDepthClassifierLike[DI] {
  val num_labels = 2
}

class FixedDepthLinearClassifier(weights: VectorAggregate, val num_labels: Int)
    extends LinearClassifier(weights)
    with FixedDepthClassifierLike[FeatureVector] {
}

class VariableDepthLinearClassifier(weights: VectorAggregate)
    extends LinearClassifier(weights)
      with VariableDepthClassifierLike[FeatureVector] {
  def number_of_labels(inst: FeatureVector) = inst.feature_vector.depth
}

/**
 * A binary linear classifier, created from an array of weights.  Normally
 * created automatically by one of the trainer classes.
 */
class BinaryLinearClassifier (
  weights: SingleVectorAggregate
) extends LinearClassifier(weights)
     with BinaryClassifierLike[FeatureVector] {

  /** Classify a given instance, returning the label, either 0 or 1. */
  override def classify(inst: FeatureVector) = {
    val sc = binary_score(inst)
    if (sc > 0) 1 else 0
  }

  /** Score a given instance, returning a single real number.  If the score
    * is &gt; 0, 1 is predicted, else 0. */
  def binary_score(inst: FeatureVector) = inst.dot_product(weights(0), 1)

  override def score(inst: FeatureVector) =
      IndexedSeq(0, binary_score(inst))
}

/**
 * Class for training a classifier given a set of training instances and
 * associated labels.
 *
 * @tparam DI Type of data instance used in training the classifier.
 */
trait ClassifierTrainer[DI]
    extends ClassifierLike[DI] {
}

/**
 * Class for training a linear classifier given a set of training instances and
 * associated labels.
 *
 * @tparam DI Type of data instance used in training the classifier.
 */
trait LinearClassifierTrainer[DI <: DataInstance]
    extends ClassifierTrainer[DI]
    with LinearClassifierLike[DI] {
  val factory: VectorAggregateFactory

  /** Check that the arguments passed in are kosher, and return an array of
    * the weights to be learned. */
  def initialize(data: Iterable[(DI, LabelIndex)]) = {
    FeatureVector.check_same_mappers(data.view.map(_._1.feature_vector))
    val len = data.head._1.feature_vector.length
    val weights = new_weights(len)
    for ((inst, label) <- data) {
      val max_label = weights.max_label min inst.feature_vector.max_label
      require(label >= 0 && label <= max_label,
        "Label %s out of bounds: [%s,%s]" format (label, 0, max_label))
    }
    weights
  }

  /** Create and initialize a vector of weights of length `len`.
    * By default, initialized to all 0's, but could be changed. */
  def new_weights(len: Int) = new_zero_weights(len)

  /** Create and initialize a vector of weights of length `len` to all 0's. */
  def new_zero_weights(len: Int) = {
    errprint("Length of weight vector: %s", len)
    factory.empty(len)
  }

  /** Iterate over a function to train a linear classifier.
    * @param error_threshold Maximum allowable error
    * @param max_iterations Maximum allowable iterations
    *
    * @param fun Function to iterate over; Takes a int (iteration number),
    *   returns a tuple (num_errors, num_adjustments, total_adjustment)
    *   where `num_errors` is the number of errors made on the training
    *   data and `total_adjustment` is the total sum of the scaling factors
    *   used to update the weights when a mistake is made. */
  def iterate(error_threshold: Double, max_iterations: Int)(
      fun: Int => (Int, Int, Double)) = {
    val task = new Meter("running", "classifier training iteration",
      verbose = MsgVerbose)
    task.start()
    var iter = 0
    var total_adjustment = 0.0
    do {
      iter += 1
      val (num_errors, num_adjustments, total_adjustment2) = fun(iter)
      total_adjustment = total_adjustment2 // YUCK!
      errprint("Iteration %s, num errors %s, num adjustments %s, total_adjustment %s",
        iter, num_errors, num_adjustments, total_adjustment)
      task.item_processed()
    } while (iter < max_iterations && total_adjustment >= error_threshold)
    task.finish()
    iter
  }

  /** Iterate over a function to train a linear classifier,
    * optionally averaging over the weight vectors at each iteration.
    * @param weights DOCUMENT ME
    * @param averaged If true, average the weights at each iteration
    * @param error_threshold Maximum allowable error
    * @param max_iterations Maximum allowable iterations
    *
    * @param fun Function to iterate over; Takes a weight vector to update
    *   and an int (iteration number), returns a tuple (num_errors,
    *   total_adjustment) where `num_errors` is the number of errors made on
    *   the training data and `total_adjustment` is the total sum of the
    *   scaling factors used to update the weights when a mistake is made. */
  def iterate_averaged(data: Iterable[(DI, LabelIndex)],
      weights: VectorAggregate, averaged: Boolean, error_threshold: Double,
      max_iterations: Int)(
      fun: (VectorAggregate, Int) => (Int, Int, Double)) = {
    def do_iterate(coda: => Unit) =
      iterate(error_threshold, max_iterations){ iter =>
        val retval = fun(weights, iter)
        errprint("Weight sum: %s", format_double(weights.sum))
        if (debug("weights-each-iteration"))
          LinearClassifier.debug_print_weights(weights,
            data.head._1.feature_vector.format_feature _)
        coda
        retval
      }
    if (!averaged) {
      val num_iterations = do_iterate { }
      (weights, num_iterations)
    } else {
      val avg_weights = new_zero_weights(weights.length)
      val num_iterations = do_iterate { avg_weights += weights }
      avg_weights *= 1.0 / num_iterations
      (avg_weights, num_iterations)
    }
  }

  /** Compute the weights used to initialize a linear classifier.
    *
    * @return Tuple of weights and number of iterations required
    *   to compute them. */
  def get_weights(training_data: TrainingData[DI]
    ): (VectorAggregate, Int)

  /** Create a linear classifier. */
  def create_classifier(weights: VectorAggregate): LinearClassifier

  /** Train a linear classifier given a set of labeled instances. */
  def apply(training_data: TrainingData[DI]) = {
    if (debug("training-data"))
      training_data.pretty_print()

    // Print most "relevant" features. See comment under
    // training_data.compute_feature_discrimination.
    // FIXME: This is designed for the reranker, with non-label-attached
    // features.
    if (debug("feature-relevance")) {
      val feat_disc = training_data.compute_feature_discrimination

      def print_feats(feats: IndexedSeq[(FeatIndex, Double, Double)]) {
        val format_feature =
          training_data.data.head._1.feature_vector.format_feature _
        for (((f, corr, sig), index) <- feats.zipWithIndex) {
          errprint(
            s"#${index + 1}: ${format_feature(f)} ($f) = $corr (sig $sig)")
        }
      }

      val min_sig = ClassifierConstants.min_sig
      val feat_corr =
        feat_disc.toIndexedSeq.sortBy(-_._2).filter(_._3 >= min_sig)
      val num_stats = ClassifierConstants.weights_to_print
      errprint("Most positively correlated features (with sig >= $min_sig):")
      print_feats(feat_corr.take(num_stats))
      errprint("Most negatively correlated features (with sig >= $min_sig):")
      print_feats(feat_corr.takeRight(num_stats).reverse)
    }

    val (weights, _) = get_weights(training_data)

    // Output highest weights.
    // FIXME: This is designed for the reranker, with non-label-attached
    // features.
    if (debug("weights")) {
      val stats = new FeatureStats
      for ((inst, label) <- training_data.data) {
        val agg = AggregateFeatureVector.check_aggregate(inst)
        stats.accumulate(agg, do_sum = true)
      }
      val format_feature =
        training_data.data.head._1.feature_vector.format_feature _
      LinearClassifier.debug_print_weights(weights, format_feature,
        Some(stats))
    }

    // Write all weights to a file, along with number of documents the
    // corresponding feature is in and sum of feature values.
    // FIXME: This is designed for the rough-to-fine classifier and
    // assumes TADM-style label-attached weights, with a single long
    // weight vector that virtually encompasses all the weights for the
    // different labels (cells). Things need to be changed to handle the
    // case with non-label-attached weights and multiple weight vectors,
    // one per label.
    val weights_filename = debugval("write-weights")
    if (weights_filename != "") {
      val weights_file = localfh.openw(weights_filename)
      val stats = new FeatureStats
      for ((inst, label) <- training_data.data) {
        val agg = AggregateFeatureVector.check_aggregate(inst)
        stats.accumulate_doc_level(agg, do_abssum = true)
      }
      assert_==(weights.depth, 1)
      val rawvec = weights(0)

      val mapper =
        training_data.data.head._1.feature_vector.mapper
      // Go through all the label-attached features. Output the
      // feature property, label (i.e. cell), weight, #docs containing
      // feature property, sum of feature property values
      for (i <- 0 until rawvec.length) {
        val weight = rawvec(i)
        val (prop, label) = mapper.feature_property_label_index(i)
        val propstr = mapper.feature_property_to_string(prop)
        val labelstr = mapper.label_to_string(label)
        val propcount = stats.count(prop)
        val propabssum = stats.abssum(prop)
        weights_file.println(
          s"$propstr\t$labelstr\t$weight\t$propcount\t$propabssum")
      }
      weights_file.close()
    }

    create_classifier(weights)
  }
}

/**
 * Mix-in for training a classifier that supports the possibility of multiple
 * correct labels for a given training instance.
 *

 * @tparam DI Type of data instance used in training the classifier.
 */
trait MultiCorrectLabelClassifierTrainer[DI]
    extends ClassifierTrainer[DI] {
  /** Return set of "yes" labels associated with an instance.  Currently only
    * one yes label per instance, but this could be changed by redoing this
    * function. */
  def yes_labels(inst: DI, label: LabelIndex) =
    (0 until 0) ++ (label to label)

  /** Return set of "no" labels associated with an instance -- complement of
    * the set of "yes" labels. */
  def no_labels(inst: DI, label: LabelIndex) =
    (0 until label) ++ ((label + 1) until number_of_labels(inst))
}

/**
 * Class for training a linear classifier with a single weight vector for
 * all labels.  This is used both for binary and multi-label classifiers.
 * In the binary case, having a single set of weights is natural, since
 * we use a single predicted score to specify both which of the two
 * labels to choose and how much confidence we have in the label.
 *
 * A single-weight multi-label classifier, i.e. there is a single combined
 * weight array for all labels, where the array is indexed both by feature
 * and label and can supply an arbitrary mapping down to the actual vector
 * of vector of values used to store the weights.  This allows for all sorts
 * of schemes that tie some weights to others, e.g. tying two features
 * together across all labels, or two labels together across a large subset
 * of features.  This, naturally, subsumes the multi-weight variant as a
 * special case.
 *
 * In the multi-label case, having a single weight vector is less obvious
 * because there are multiple scores predicted, generally one per potential
 * label.  In a linear classifier, each score is predicted using a
 * dot product of a weight vector with the instance's feature vector,
 * meaning there needs to be a separately trained set of weights for each
 * label.  The single weight vector actually includes all weights for all
 * labels concatenated inside it in some fashion; see the description in
 * `SingleWeightMultiLabelLinearClassifier`.
 */
trait SingleWeightLinearClassifierTrainer[DI <: DataInstance]
    extends LinearClassifierTrainer[DI]
    with VariableDepthClassifierLike[DI] {
  val vector_factory: SimpleVectorFactory
  lazy val factory = new SingleVectorAggregateFactory(vector_factory)

  def create_classifier(weights: VectorAggregate) =
    new VariableDepthLinearClassifier(weights)
  def number_of_labels(inst: DI) = inst.feature_vector.depth
}

/**
 * Class for training a multi-weight linear classifier., i.e. there is a
 * different set of weights for each label.  Note that this can be subsumed
 * as a special case of the single-weight multi-label classifier because the
 * feature vectors are indexed by label.  To do this, expand the weight vector
 * to include subsections for each possible label, and expand the feature
 * vectors so that the feature vector corresponding to a given label has
 * the original feature vector occupying the subsection corresponding to
 * that label and 0 values in all other subsections.
 */
trait MultiWeightLinearClassifierTrainer[DI <: DataInstance]
    extends LinearClassifierTrainer[DI]
    with FixedDepthClassifierLike[DI] {
  val vector_factory: SimpleVectorFactory
  lazy val factory = new MultiVectorAggregateFactory(vector_factory, num_labels)

  def create_classifier(weights: VectorAggregate) =
    new FixedDepthLinearClassifier(weights, num_labels)
}

/**
 * Class for training a binary linear classifier given a set of training
 * instances and associated labels.
 */
trait BinaryLinearClassifierTrainer[DI <: DataInstance]
    extends LinearClassifierTrainer[DI]
    with BinaryClassifierLike[DI] {
  val vector_factory: SimpleVectorFactory
  lazy val factory = new SingleVectorAggregateFactory(vector_factory)

  def create_classifier(weights: VectorAggregate) =
    new BinaryLinearClassifier(weights.asInstanceOf[SingleVectorAggregate])
}

/**
 * Class for training a single-weight multi-label linear classifier.
 */
trait SingleWeightMultiLabelLinearClassifierTrainer[DI <: DataInstance]
  extends SingleWeightLinearClassifierTrainer[DI] {
}

/**
 * Class for training a multi-weight multi-label linear classifier.
 */
trait MultiWeightMultiLabelLinearClassifierTrainer[DI <: DataInstance]
  extends MultiWeightLinearClassifierTrainer[DI] {
}
