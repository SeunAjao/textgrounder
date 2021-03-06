///////////////////////////////////////////////////////////////////////////////
//  CophirDoc.scala
//
//  Copyright (C) 2011-2014 Ben Wing, The University of Texas at Austin
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
package geolocate

import util.spherical._
import util.textdb.Schema

import gridlocate._

class CophirDoc(
  schema: Schema,
  lang_model: DocLangModel,
  coord: SphereCoord,
  salience: Option[Double],
  val id: Long,
  val user: Long
) extends RealSphereDoc(schema, lang_model, coord, salience) {
  def title = id.toString
}

class CophirDocSubfactory(
  docfact: SphereDocFactory
) extends SphereDocSubfactory[CophirDoc](docfact) {
  def create_document(rawdoc: RawDoc, lang_model: DocLangModel,
      coord: SphereCoord) = {
    new CophirDoc(rawdoc.row.schema, lang_model, coord,
      rawdoc.row.get_if[Double]("salience"),
      rawdoc.row.get_or_else[Long]("photo-id", 0L),
      rawdoc.row.get_or_else[Long]("owner-id", 0L))
  }
}
