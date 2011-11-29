///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2011 Ben Wing, The University of Texas at Austin
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

package opennlp.textgrounder.preprocess

import collection.mutable

import opennlp.textgrounder.util.argparser._
import opennlp.textgrounder.util.experiment.ExperimentDriverApp
import opennlp.textgrounder.util.ioutil.FileHandler

/////////////////////////////////////////////////////////////////////////////
//                                  Main code                              //
/////////////////////////////////////////////////////////////////////////////

class FrobCorpusParameters(ap: ArgParser) extends
    ProcessCorpusParameters(ap) {
  val suffix =
    ap.option[String]("s", "suffix",
      default = "unigram-counts",
      metavar = "DIR",
      help = """Suffix used to select the appropriate files to operate on.
Default '%default'.""")
  val add_field =
    ap.multiOption[String]("a", "add-field",
      metavar = "FIELD=VALUE",
      help = """Add a field named FIELD, with the value VALUE, to all rows.
Fields are added at the beginning.  If this argument is given multiple times,
the fields are added in order.""")
  val remove_field =
    ap.multiOption[String]("r", "remove-field",
      metavar = "FIELD",
      help = """Remove a field from all rows.""")
  val set_split_by_value =
    ap.option[String]("set-split-by-value",
      metavar = "SPLITFIELD,MAX-TRAIN-VAL,MAX-DEV-VAL",
      help = """Set the "split" field to one of "training", "dev" or "test"
according to the value of another field (e.g. by time).  For the field named
SPLITFIELD, values <= MAX-TRAIN-VAL go into the training split;
values <= MAX-DEV-VAL go into the dev split; and higher values go into the
test split.  Comparison is lexicographically (i.e. string comparison,
rather than numeric).""")

  var split_field: String = null
  var max_training_val: String = null
  var max_dev_val: String = null
}

/**
 * A field-text file processor that outputs fields as the came in,
 * possibly modified in various ways.
 *
 * @param input_suffix suffix used to retrieve schema and document files in
 *   in the input corpus
 * @param output_filehand FileHandler of the output corpus (directory is
 *   taken from parameters)
 * @param params Parameters retrieved from the command-line arguments
 */
class FrobCorpusFileProcessor(
  input_suffix: String, output_filehand: FileHandler,
  params: FrobCorpusParameters
) extends ProcessCorpusFileProcessor(
  input_suffix, params.suffix, output_filehand, params.output_dir
) {
  def frob_row(fieldvals: Seq[String]) = {
    val zipped_vals = (schema zip fieldvals)
    val new_fields =
      for (addfield <- params.add_field) yield {
        val Array(field, value) = addfield.split("=", 2)
        (field -> value)
      }
    val docparams = mutable.LinkedHashMap[String, String]()
    docparams ++= new_fields
    docparams ++= zipped_vals
    for (field <- params.remove_field)
      docparams -= field
    if (params.split_field != null) {
      if (docparams(params.split_field) <= params.max_training_val)
        docparams("split") = "training"
      else if (docparams(params.split_field) <= params.max_dev_val)
        docparams("split") = "dev"
      else
        docparams("split") = "test"
    }
    docparams.toSeq
  }
}

class FrobCorpusDriver extends ProcessCorpusDriver {
  type ParamType = FrobCorpusParameters
  
  override def handle_parameters() {
    if (params.set_split_by_value != null) {
      val Array(split_field, training_max, dev_max) =
        params.set_split_by_value.split(",")
      params.split_field = split_field
      params.max_training_val = training_max
      params.max_dev_val = dev_max
    }
    super.handle_parameters()
  }

  def create_file_processor(input_suffix: String) =
    new FrobCorpusFileProcessor(input_suffix, filehand, params)

  def get_input_corpus_suffix = params.suffix
}

object FrobCorpus extends
    ExperimentDriverApp("FrobCorpus") {
  type DriverType = FrobCorpusDriver

  override def description =
"""Modify a corpus by changing particular fields.  Fields can be added
(--add-field) or removed (--remove-field), and the "split" field can be
set based on the value of another field.
"""

  def create_param_object(ap: ArgParser) = new ParamType(ap)
  def create_driver() = new DriverType
}