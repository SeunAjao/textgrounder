///////////////////////////////////////////////////////////////////////////////
//  Memoizer.scala
//
//  Copyright (C) 2011, 2012 Ben Wing, The University of Texas at Austin
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

package opennlp.textgrounder.util
package memoizer

import collection.mutable

import com.codahale.trove.{mutable => trovescala}

import collectionutil._
import printutil.errprint

trait HashTableFactory {
  /**
   * Create a mutable map from Ints to Ints, with 0 as default value.
   * (I.e., attempting to fetch a nonexistent key will yield 0 rather than
   * throw an error.)
   */
  def create_int_int_map: mutable.Map[Int, Int]
  /**
   * Create a mutable map from Ints to Doubles, with 0.0 as default value.
   * (I.e., attempting to fetch a nonexistent key will yield 0.0 rather than
   * throw an error.)
   */
  def create_int_double_map: mutable.Map[Int, Double]
  /**
   * Create a mutable map from Ints to arbitrary reference type T.
   */
  def create_int_object_map[T]: mutable.Map[Int, T]
  /**
   * Create a mutable map from arbitrary reference type T to Ints, with 0
   * as default value. (I.e., attempting to fetch a nonexistent key will
   * yield 0 rather than throw an error.)
   */
  def create_object_int_map[T]: mutable.Map[T, Int]
}

class ScalaHashTableFactory extends HashTableFactory {
  def create_int_int_map = intmap[Int]()
  def create_int_double_map = doublemap[Int]()
  def create_int_object_map[T] = mutable.Map[Int,T]()
  def create_object_int_map[T] = intmap[T]()
}

/*
 * Use Trove for extremely fast and memory-efficient hash tables, making use of
 * the Trove-Scala interface for easy access to the Trove hash tables.
 */
class TroveHashTableFactory extends HashTableFactory {
  def create_int_int_map = trovescala.IntIntMap()
  def create_int_double_map = trovescala.IntDoubleMap()
  def create_int_object_map[T] = trovescala.IntObjectMap[T]()
  def create_object_int_map[T] = trovescala.ObjectIntMap[T]()
}

/**
 * A class for "memoizing" values, i.e. mapping them to some other type
 * (e.g. Int) that should be faster to compare and potentially require
 * less space.
 *
 * @tparam T Type of unmemoized value.
 * @tparam U Type of memoized value.
 */
trait Memoizer[T,U] {
  /**
   * Map a value to its memoized form.
   */
  def memoize(value: T): U
  /**
   * Map a value out of its memoized form.
   */
  def unmemoize(value: U): T
}

/**
 * Standard memoizer for mapping values to Ints.  It is suggested to use
 * TroveHashTableFactory for the hash-table factory, for efficiency.
 *
 * @param hashfact Hash-table factory for generating hash tables.
 * @param minimum_index Smallest index returned. Can be changed to reserve
 *   some indices for other purposes.
 */
class ToIntMemoizer[T](
  val hashfact: HashTableFactory,
  val minimum_index: Int = 0
) extends Memoizer[T,Int] {
  protected var next_index: Int = minimum_index

  def number_of_entries = next_index - minimum_index
  
  // For replacing items with ints.  This should save space on 64-bit
  // machines (object pointers are 8 bytes, ints are 4 bytes) and might
  // also speed lookup.
  protected val value_id_map = hashfact.create_object_int_map[T]

  // Map in the opposite direction.
  protected val id_value_map = hashfact.create_int_object_map[T]

  def memoize(value: T) = {
    val lookup = value_id_map.get(value)
    // println("Saw value=%s, index=%s" format (value, lookup))
    lookup match {
      case Some(index) => index
      case None => {
        val newind = next_index
        next_index += 1
        value_id_map(value) = newind
        id_value_map(newind) = value
        newind
      }
    }
  }

  def unmemoize(index: Int) = id_value_map(index)
}

/**
 * The memoizer we actually use.  Maps word strings to Ints.  Uses Trove
 * for extremely fast and memory-efficient hash tables, making use of the
 * Trove-Scala interface for easy access to the Trove hash tables.
 */
class IntIntMemoizer(
  hashfact: HashTableFactory,
  minimum_index: Int = 0
) extends ToIntMemoizer[Int](hashfact, minimum_index) {
  protected override val value_id_map = hashfact.create_int_int_map

  // Map in the opposite direction.
  protected override val id_value_map = hashfact.create_int_int_map
}

/**
 * Version for debugging the String-to-Int memoizer.
 */
class TestStringIntMemoizer(
  hashfact: HashTableFactory,
  minimum_index: Int = 0
) extends ToIntMemoizer[String](hashfact, minimum_index) {
  override def memoize(value: String) = {
    val cur_nwi = next_index
    val index = super.memoize(value)

    // if (debug("memoize")) {
    {
      if (next_index != cur_nwi)
        errprint("Memoizing new string %s to ID %s", value, index)
      else
        errprint("Memoizing existing string %s to ID %s", value, index)
    }

    assert(super.unmemoize(index) == value)
    index
  }

  override def unmemoize(value: Int) = {
    if (!(id_value_map contains value)) {
      errprint("Can't find ID %s in id_value_map", value)
      errprint("Word map:")
      var its = id_value_map.toList.sorted
      for ((key, value) <- its)
        errprint("%s = %s", key, value)
      assert(false, "Exiting due to bad code in unmemoize")
      null
    } else {
      val string = super.unmemoize(value)

      // if (debug("memoize"))
        errprint("Unmemoizing existing ID %s to string %s", value, string)

      assert(super.memoize(string) == value)
      string
    }
  }
}

/**
 * A memoizer for testing that doesn't actually do anything -- the memoized
 * values are the same as the unmemoized values.
 */
class IdentityMemoizer[T] extends Memoizer[T,T] {
  def memoize(value: T) = value
  def unmemoize(value: T) = value
}

