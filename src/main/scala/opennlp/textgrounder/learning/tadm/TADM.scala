///////////////////////////////////////////////////////////////////////////////
//  TADM.scala
//
//  Copyright (C) 2013-2014 Ben Wing, The University of Texas at Austin
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
package learning.tadm

import scala.sys.process._

import learning._
import util.io.localfh
import util.metering._

/**
 * This implements an interface onto the TADM classifier and ranker.
 * There are two interfaces, one that calls the Python scripts that
 * interface onto the C++ TADM executables and another that interfaces
 * directly onto the executables themselves. The former is present for
 * testing and to see how exactly the TADM event-format files need to
 * be structured.
 *
 * @author Ben Wing
 *
 * The regular classifier is equivalent to a straight multinomial logit,
 * while the ranker is equivalent to a conditional logit. See MLogit.scala
 * for more information about these models.
 *
 * TADM is capable of doing both L1 (Lasso) and L2 (Ridge/Tikhonov)
 * regularization. Ridge regularization adds a constraint that the sum of
 * the squares of all the weights must be below some value; or equivalently
 * it adds a term to the objective function to be minimized that consists of
 * some regularization constant (or "penalty") times the sum of the squares
 * of all the weights; or equivalently, it puts zero-mean Gaussian priors on
 * the weights, with the standard deviation serving as the penalty term.
 * Lasso regularization is similar, but uses the absolute value of a weight in
 * place of its square; or equivalently, it puts zero-mean Laplace priors on
 * the weights.
 *
 * Lasso has the property that it drives some of the less important weights
 * to 0, and as the penalty is increased, more weights will be driven to 0.
 * Ridge regularization, simply makes the weights smaller with greater
 * penalty but doesn't set them to 0. Thus, Lasso finds a sparse solution,
 * somewhat like what SVM's do.
 *
 * Write out the training data to a file in the format desired by TADM.
 *
 * TADM-Python assumes the following format for data in the Simple format
 * (for classification):
 *
 * -- Each data instance consists of a single line, the correct label followed
 *    by the features for the candidate, separated by spaces. Only binary features
 *    can be specified this way.
 *
 * TADM-Python assumes the following format for data in the SimpleRanker
 * format:
 *
 * -- Each data instance consists of a number of lines: first a line
 *    containing only a number giving the number of candidates, followed
 *    by one line per candidate.
 * -- Each line consists of label followed by the features for the candidate,
 *    separated by spaces. Only binary features can be specified this way.
 * -- The correct candidate is the one that has the label "1".
 *
 * TADM itself wants an events-in file in the following format for ranking:
 *
 * -- Each data instance consists of a number of lines: first a line
 *    containing only a number giving the number of candidates, followed
 *    by one line per candidate.
 * -- Each line consists of a "frequency of observation" followed by the
 *    number of feature-value pairs followed by a feature, then a value,
 *    repeated for the total number of pairs. All items are separated by
 *    spaces. The features should be integers, numbered starting at 0.
 * -- The correct candidate should have a frequency of 1, and the other
 *    candidates should have a frequency of 0.
 *
 * For classification, TADM uses the same events-in file format, structured
 * as follows:
 *
 * -- For a given instance, in place of there being one line per candidate,
 *    there is one line per label, specifying the features that occur for
 *    the instance in combination with the given label.
 * -- In the common case where the features of an instance are not
 *    label-specific, it's necessary to create separately-numbered features
 *    for use with each separate label. For example, in the "tennis" example,
 *    the feature "Outlook=Sunny" corresponds to separately-numbered features
 *    for label "Yes" and label "No". Contrast this with the case of a ranker,
 *    where the same-numbered feature may occur with several candidates. The
 *    effect separately numbering the features like this is the equivalent of
 *    having separate weight vectors for each label.
 * -- The correct label still has a frequency of 1, and the others 0.
 *
 * Because of the above, the difference between classifier and ranker needs to
 * be handled entirely by the caller. In both cases, AggregateFeatureVectors
 * are passed in, and the return value is a SingleVectorAggregate.
 */

/**
 * Train a classifying or ranking model using TADM.
 */
class TADMTrainer[DI <: DataInstance](
  val vector_factory: SimpleVectorFactory,
  val max_iterations: Int,
  val gaussian: Double,
  val lasso: Double,
  val method: String = "tao_lmvm",
  val uniform_marginal: Boolean = false
) extends SingleWeightMultiLabelLinearClassifierTrainer[DI] {
  /**
   * Write out the set of instances to a TADM events file and run TADM,
   * returning the weights.
   */
  def get_weights(training_data: TrainingData[DI]): (VectorAggregate, Int) = {
    val events_filename =
      java.io.File.createTempFile("textgrounder.tadm.events", null).toString
    val params_filename =
      java.io.File.createTempFile("textgrounder.tadm.params", null).toString
    training_data.export_to_file(events_filename, include_length = true,
      memoized_features = true)
    val tadm_cmd_line_start =
      Seq("tadm", "-monitor", "-method", method, "-events_in",
        events_filename, "-params_out", params_filename, "-max_it",
        s"$max_iterations")
    val tadm_penalty =
      (if (gaussian > 0) Seq("-l2", s"$gaussian") else Seq()) ++
      (if (lasso > 0) Seq("-l1", s"$lasso") else Seq())
    val tadm_marginal =
      if (uniform_marginal) Seq("-uniform")
      else Seq()
    time_action("running TADM") {
      (tadm_cmd_line_start ++ tadm_penalty ++ tadm_marginal) !
    }
    val weights =
      localfh.openr(params_filename).map { w => w.toDouble }.toArray
    // If we deleted the highest-numbered feature(s), we may have a
    // weight vector smaller than required and need to pad with zeros.
    // Retrieve total number of features
    val head = training_data.data.head._1.feature_vector
    val F = head.mapper.feature_vector_length
    val expanded_weights =
      if (weights.size < F) {
        // Make sure all missing features were actually removed.
        for (ind <- weights.size until F)
          assert(training_data.removed_features contains ind,
            s"${training_data.removed_features} does not contain $ind")
        weights ++ Seq.fill(F - weights.size)(0.0)
      } else
        weights
    val vecagg = SingleVectorAggregate(ArrayVector(expanded_weights.toArray))
    // FIXME: Retrieve actual number of iterations, or just get rid of the
    // need to return this value at all.
    (vecagg, 1)
  }
}
