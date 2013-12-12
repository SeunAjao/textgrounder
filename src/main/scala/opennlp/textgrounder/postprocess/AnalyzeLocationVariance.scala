//  AnalyzeLocationVariance.scala
//
//  Copyright (C) 2013 Ben Wing, The University of Texas at Austin
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
package postprocess

import collection.mutable

import util.argparser._
import util.experiment._
import util.io
import util.json._
import util.math._
import util.print._
import util.spherical._
import util.textdb._
import util.time._

import util.debug._

class AnalyzeLocationVarianceParameters(ap: ArgParser) {
  var location_histogram = ap.option[String]("location-histogram",
    metavar = "FILE",
    help = """Output a histogram of location-variance information
to the specified file, which should end in .pdf.""")

  var input = ap.positional[String]("input",
    help = """Corpus to analyze, a textdb database.  This needs to have
a 'positions' field, as generated by ParseTweets with 'positions'
specified as one of the fields in '--output-fields'.

The value of the parameter can be any of the following: Either the data
or schema file of the database; the common prefix of the two; or the
directory containing them, provided there is only one textdb in the
directory.""")
}

/**
 * An application to analyze the different positions seen in a given user's
 * tweets. This assumes that ParseTweets has been run with the argument
 * --output-fields 'default positions' so that a field is included that
 * contains all the locations in a user's tweets.
 */
object AnalyzeLocationVariance extends ExperimentApp("AnalyzeLocationVariance") {

  type TParam = AnalyzeLocationVarianceParameters
  type Timestamp = Long

  def create_param_object(ap: ArgParser) = new AnalyzeLocationVarianceParameters(ap)

  def initialize_parameters() {
  }

  def cartesian_product[T1, T2](A: Iterable[T1], B: Iterable[T2]
      ): Iterable[(T1, T2)] = {
    for (a <- A; b <- B) yield (a, b)
  }

  def format_fraction(frac: (Double, Double)) =
    "%.2f%%,%.2f%%" format (frac._1 * 100, frac._2 * 100)

  def fraction_from_bounding_box(sw: SphereCoord, ne: SphereCoord,
      point: SphereCoord) = {
    ((point.lat - sw.lat) / (ne.lat - sw.lat),
     (point.long - sw.long) / (ne.long - sw.long))
  }

  case class Position(
    time: Timestamp,
    coord: SphereCoord
  )

  case class User(
    user: String,
    coord: SphereCoord,
    positions: Iterable[Position]
  )

  case class LocationStats(
    user: User,
    bounding_box_sw: SphereCoord,
    bounding_box_ne: SphereCoord,
    dist_across_bounding_box: Double,
    centroid: SphereCoord,
    avgdist_from_centroid: Double,
    mindist_from_centroid: Double,
    quantile25_dist_from_centroid: Double,
    median_dist_from_centroid: Double,
    quantile75_dist_from_centroid: Double,
    maxdist_from_centroid: Double,
    dist_from_centroid_variance: Double,
    earliest: Position,
    avgdist_from_earliest: Double,
    latest: Position,
    maxdist_between_points: Double
  ) {
    def get_fraction(coord: SphereCoord) =
      fraction_from_bounding_box(bounding_box_sw, bounding_box_ne, coord)

    def get_format_fraction(coord: SphereCoord) =
      format_fraction(get_fraction(coord))

    def output_position(pos: Position, tag: String) {
      errprint("  %s: %s at fraction %s, %.2f km from centroid",
        tag, pos.coord.format, get_format_fraction(pos.coord),
        spheredist(pos.coord, centroid))
      errprint("  %s time: %s", tag, format_time(pos.time * 1000))
    }

    def pretty_print() {
      errprint("User: %s, %s positions", user.user, user.positions.size)
      errprint("  bounding box: %s to %s, dist across: %.2f km",
        bounding_box_sw.format, bounding_box_ne.format,
        dist_across_bounding_box)
      errprint("  centroid: %s at fraction %s",
        centroid.format, get_format_fraction(centroid))
      output_position(earliest, "earliest")
      output_position(latest, "latest")
      errprint("  dist from centroid: avg %.2f km, median %.2f km, std dev %.2f km",
        avgdist_from_centroid, median_dist_from_centroid,
        math.sqrt(dist_from_centroid_variance))
      errprint("  quantiles dist from centroid in km:")
      errprint("    min %.2f, 25%% %.2f, median %.2f, 75%% %.2f, max %.2f",
        mindist_from_centroid, quantile25_dist_from_centroid,
        median_dist_from_centroid, quantile75_dist_from_centroid,
        maxdist_from_centroid)
      errprint("  max dist between points: %.2f km", maxdist_between_points)
    }
  }

  /** Compute centroid, average distance from centroid, max distance
    * from centroid, variance of distances, max distance between any two
    * points. */
  def compute_location_stats(user: User) = {
    val positions = user.positions.toIndexedSeq
    val points = positions.map(_.coord)
    val centroid = SphereCoord.centroid(points)
    val raw_distances = positions.map {
      case Position(ts, coord) => (ts, spheredist(coord, centroid))
    }
    val distances = raw_distances.map(_._2).sorted
    val ts_points_by_time = positions.sortBy(_.time)
    val earliest = ts_points_by_time.head
    val latest = ts_points_by_time.last
    val raw_distances_from_earliest = positions.map {
      case Position(ts, coord) => (ts, spheredist(coord, earliest.coord))
    }
    val distances_from_earliest = raw_distances_from_earliest.map(_._2).sorted
    val bounding_box_sw = SphereCoord.bounding_box_sw(points)
    val bounding_box_ne = SphereCoord.bounding_box_ne(points)

    LocationStats(
      user = user,
      bounding_box_sw = bounding_box_sw,
      bounding_box_ne = bounding_box_ne,
      dist_across_bounding_box = spheredist(bounding_box_sw, bounding_box_ne),
      centroid = centroid,
      avgdist_from_centroid = mean(distances),
      mindist_from_centroid = distances.head,
      quantile25_dist_from_centroid = quantile_at(distances, 0.25),
      median_dist_from_centroid = quantile_at(distances, 0.5),
      quantile75_dist_from_centroid = quantile_at(distances, 0.75),
      maxdist_from_centroid = distances.last,
      //maxdist_between_points = cartesian_product(points, points).map {
      //  case (a, b) => spheredist(a, b)
      //}.max,
      dist_from_centroid_variance = variance(distances),
      earliest = earliest,
      avgdist_from_earliest = mean(distances_from_earliest),
      latest = latest,
      // FIXME: This is O(N^2)! Seems to me that the maximum distance
      // between any two points should involve two points on the convex
      // hull, so we first should compute the convex hull and eliminate
      // points not on the hull.
      maxdist_between_points = 0.0 // FIXME: Don't calculate for now
    )
  }

  /**
   * Output a set of histograms to a specified file, using R. Each
   * histogram is a separate plot, and the entire set of histograms will
   * be displayed in a grid of size `width` by `height`, with each plot
   * labeled with the specified label.
   *
   * @param file File to output to; will be in PDF format.
   * @param runs Data to plot. Should consist of a series of pairs of
   *   label and run, which is a set of numbers.
   * @param width Number of plots to display across.
   * @param height Number of plots to display down.
   */
  def compute_histogram(file: String,
      runs: Iterable[(String, Iterable[Double])],
      width: Int, height: Int) {
    import org.ddahl.jvmr.RInScala

    assert(width * height >= runs.size)
    val R = RInScala()
    for (((label, run), index) <- runs.zipWithIndex) {
      R.update("data%s" format index, run.toArray)
      R.update("label%s" format index, label)
    }
    val plothist_calls = (for (index <- 0 until runs.size) yield
      "plothist(data%s, label%s)" format (index, index)) mkString "\n"
    val rcode = """
plothist = function(vals, title) {
  #vals = read.table(file)
  #vals = vals[,]
  lvals = log(vals)
  h = hist(lvals, breaks=50, freq=FALSE, plot=FALSE)
  d = density(lvals)
  #ylimit = range(0, h$density, d$y)
  ylimit = c(0, 0.3)
  xlimit = range(h$breaks, d$x)
  xlimit[1] = 0
  xlabel = sprintf("%s (km)", title)
  # NOTE: Trying to specify the labels here and control the font size
  # using cex doesn't seem to work. You need to use cex.lab for the labels
  # and cex.axis for the axis. Likewise in axis(), cex doesn't work
  # and you have to use cex.axis.
  hist(lvals, breaks=50, freq=FALSE, xlim=xlimit, ylim=ylimit, xlab="",
       ylab="", main="", col="lightgrey", border="darkgrey", xaxt="n")
  myat = xlimit[1]:(xlimit[2]*2)/2
  lines(d$x, d$y)
  axis(1, at=myat, labels=formatC(exp(myat), format="f", digits=2,
                                  drop0trailing=TRUE))
  # cex works here but not the way you expect. cex=1 actually multiplies
  # the default font size by 1.5! You need to use 0.67 to get the expected
  # default font size.
  mtext(xlabel, side=1, line=2.5, cex=1.1)
  mtext("Density", side=2, line=2.5, cex=1.1)
}
""" + s"""
pdf("$file", pointsize=8)

par(mfrow=c($height,$width))

${plothist_calls}

dev.off()
"""
    R.eval(rcode)
  }

  def run_program(args: Array[String]) = {
    val filehand = io.localfh
    val users = TextDB.read_textdb(filehand, params.input) map { row =>
      val positions = Decoder.string_map_seq(row.gets("positions")).
        map {
          case (timestamp, coord) => Position(
            timestamp.toLong, SphereCoord.deserialize(coord))
        }
      User(row.gets("user"), SphereCoord.deserialize(row.gets("coord")),
        positions)
    }
    val stats = users.map { user =>
      val stat = compute_location_stats(user)
      // pretty_json(stat)
      stat.pretty_print
      stat
    }.toList
    if (params.location_histogram != null) {
      val runs = Seq(
        "dist across bounding box" ->
          stats.map { _.dist_across_bounding_box },
        "dist earliest from centroid" ->
          stats.map { x => spheredist(x.earliest.coord, x.centroid) },
        "max dist from centroid" ->
          stats.map { _.maxdist_from_centroid },
        "avg dist from centroid" ->
          stats.map { _.avgdist_from_centroid },
        "avg dist from earliest" ->
          stats.map { _.avgdist_from_earliest },
        "std dev distance from centroid" ->
          stats.map { x => math.sqrt(x.dist_from_centroid_variance) }
      )
      compute_histogram(params.location_histogram, runs, 3, 2)
    }
    0
  }
}
