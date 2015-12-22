package com.stderr.mandelbulb

import java.io.{BufferedOutputStream, FileOutputStream}

import org.apache.log4j.{Level, Logger, BasicConfigurator}
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD

import scala.reflect.io.File

/*
* Ray marching MandelBulb in Scala
*/
object Main {
  val logger = Logger.getLogger(Main.getClass)

  def main(args: Array[String]):Unit = {
    val imageWidth = 300
    val imageHeight = 300
    val master = if (args.length > 0) args(0) else "local"
    sparkParallelComputeScene(imageWidth, imageHeight,
      mandelbulb, // use sphere for testing framework
      master)
  }

  def sparkParallelComputeScene(imageWidth: Int, imageHeight: Int, DE: Vec3 => Double, master: String) = {
    BasicConfigurator.configure()
    Logger.getRootLogger.setLevel(Level.ERROR)

    var conf = new SparkConf().setAppName("mandelbulb")
    // When running with spark submit, we do not need to set the master
    if (master.startsWith("local")) conf.setMaster(master)
    val sc = new SparkContext(conf)
    val numScenes = 180

    val scenes = sc.parallelize(0 to numScenes - 1).map(Scene(imageWidth, imageHeight, _, numScenes))
    // 8 partitions is not optimized, depends on your compute power
    val xDimension: RDD[Int] = sc.parallelize(0 to imageWidth - 1, 8)
    val yDimension: RDD[Int] = sc.parallelize(0 to imageHeight - 1, 8)
    val xy: RDD[Point] = xDimension.cartesian(yDimension).map(_ match { case (x,y) => Point(x, y) })
    val sceneXY: RDD[(Scene, Point)] = scenes.cartesian(xy)
    // Compute the marched ray
    val rays: RDD[(Scene, Point, Some[MarchedRay])] = sceneXY.map(_ match {
      case (scene: Scene, point: Point) => {
        val marcher = new RayMarcher(scene)
        val ray = marcher.computeRay(point, DE)
        (scene, point, ray)
      }})
    // Color each ray into a pixel
    val pixels: RDD[(Scene, Point, Option[Pixel], Option[MarchedRay])] = rays.map(_ match {
      case (scene: Scene, point: Point, Some(ray)) => (scene, point, ColorComputer.computeColor(scene.lightDirection, ray, DE), Some(ray))
      case (scene: Scene, point: Point, ray) => (scene, point, Some(Pixel(0, 0, 0)), ray)

    })
    // Gather the pixels grouped by scene frame
    val indexByFrame: RDD[(Int, (Scene, Point, Pixel))] = pixels.map(_ match {
      case (scene, point, Some(pixel), Some(ray)) => (scene.frame, (scene, point, pixel))
      case (scene, point, _, _) => (scene.frame, (scene, point, Pixel(255, 0, 0)))
    })
    val pixelsGroupedByFrame: RDD[(Int, Iterable[(Scene, Point, Pixel)])] = indexByFrame.groupByKey()
    // Order the pixels in the frame in parallel
    val sortedPixels: RDD[(Int, Seq[(Scene, Point, Pixel)])] = pixelsGroupedByFrame.mapValues(_.toSeq.sorted)
    // Gather the pixels from the workers by frame
    // and encode MP4 file
    val keys = sortedPixels.map(_._1).collect().sorted
    val encoder = new PixmapToMP4()
    for (key <- keys) {
      val pixels: Array[(Int, Seq[(Scene, Point, Pixel)])] = sortedPixels.filter(_._1 == key).collect
      encoder.encode(pixels(0)._2)
    }
    encoder.finish
  }

  def writeFrame(pixels: Seq[(Scene, Point, Pixel)]): Boolean = {
    if (pixels.isEmpty) false else {
      val (scene: Scene, _, _) = pixels.head
      val fileName = "frames/t%06d.ppm".format(scene.frame)
      File(fileName).parent.createDirectory(failIfExists = false)
      val out = new BufferedOutputStream(new FileOutputStream(fileName))
      val header = s"P3\n${scene.imageWidth} ${scene.imageHeight}\n255\n"
      out.write(header.getBytes)
      for ((scene, point, pixel) <- pixels) out.write(s"${pixel.red} ${pixel.green} ${pixel.blue}\n".getBytes())
      out.close()
      true
    }
  }

  /*
  * Distance estimator function for sphere.
  * See http://iquilezles.org/www/articles/distfunctions/distfunctions.htm
  */
  def sphere(v : Vec3): Double = {
    v.length - 1.0
  }

  /*
  * Distance estimator function for mandelbulb.
  */
  def mandelbulb(pos: Vec3): Double = {
    val Iterations = 20.0
    val Power = 8
    var z = Vec3(pos.x, pos.y, pos.z)
    var dr = 1.0
    var r = 0.0
    var i = 0
    while (i < Iterations) {
      r = z.length
      if (r > 2.5) {  // DEPTH_OF_FIELD
        return 0.5 * Math.log(r) * r / dr
      }
      var theta = Math.acos(z.z / r)
      var phi = Math.atan2(z.y, z.x)
      dr =  Math.pow(r, Power - 1.0) * Power * dr + 1.0
      val zr = Math.pow(r, Power)
      theta = theta * Power
      phi = phi * Power
      val sinTheta = Math.sin(theta)
      z = Vec3(sinTheta * Math.cos(phi),
        Math.sin(phi) * sinTheta,
        Math.cos(theta))
      z = (z * zr) + pos
      i += 1
    }
    return 0.5 * Math.log(r) * r / dr
  }

}
