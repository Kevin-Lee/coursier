package coursier
package core

import java.io._
import java.net.{ URI, URL }

import scala.io.Codec
import scalaz._, Scalaz._
import scalaz.concurrent.Task

case class MavenRepository(
  root: String,
  cache: Option[File] = None,
  ivyLike: Boolean = false,
  logger: Option[MavenRepository.Logger] = None
) extends BaseMavenRepository(root, ivyLike) {

  val isLocal = root.startsWith("file:/")

  def fetch(
    artifact: Artifact,
    cachePolicy: CachePolicy
  ): EitherT[Task, String, String] = {

    def locally(eitherFile: String \/ File) = {
      Task {
        for {
          f0 <- eitherFile
          f <- Some(f0).filter(_.exists()).toRightDisjunction("Not found in cache")
          content <- \/.fromTryCatchNonFatal{
            logger.foreach(_.readingFromCache(f))
            scala.io.Source.fromFile(f)(Codec.UTF8).mkString
          }.leftMap(_.getMessage)
        } yield content
      }
    }

    if (isLocal) EitherT(locally(\/-(new File(new URI(root + artifact.url) .getPath))))
    else {
      lazy val localFile = {
        for {
          cache0 <- cache.toRightDisjunction("No cache")
          f = new File(cache0, artifact.url)
        } yield f
      }

      def remote = {
        val urlStr = root + artifact.url
        val url = new URL(urlStr)

        def log = Task(logger.foreach(_.downloading(urlStr)))
        def get = {
          val conn = url.openConnection()
          // Dummy user-agent instead of the default "Java/...",
          // so that we are not returned incomplete/erroneous metadata
          // (Maven 2 compatibility? - happens for snapshot versioning metadata,
          // this is SO FUCKING CRAZY)
          conn.setRequestProperty("User-Agent", "")
          MavenRepository.readFully(conn.getInputStream())
        }
        def logEnd(success: Boolean) = logger.foreach(_.downloaded(urlStr, success))

        log
          .flatMap(_ => get)
          .map{ res => logEnd(res.isRight); res }
      }

      def save(s: String) = {
        localFile.fold(_ => Task.now(()), f =>
          Task {
            if (!f.exists()) {
              logger.foreach(_.puttingInCache(f))
              f.getParentFile.mkdirs()
              val w = new PrintWriter(f)
              try w.write(s)
              finally w.close()
              ()
            }
          }
        )
      }

      EitherT(
        cachePolicy[String \/ String](
          _.isLeft )(
          locally(localFile) )(
          _ => CachePolicy.saving(remote)(save)
        )
      )
    }
  }

}

object MavenRepository {

  trait Logger {
    def downloading(url: String): Unit
    def downloaded(url: String, success: Boolean): Unit
    def readingFromCache(f: File): Unit
    def puttingInCache(f: File): Unit
  }

  def readFullySync(is: InputStream) = {
    val buffer = new ByteArrayOutputStream()
    val data = Array.ofDim[Byte](16384)

    var nRead = is.read(data, 0, data.length)
    while (nRead != -1) {
      buffer.write(data, 0, nRead)
      nRead = is.read(data, 0, data.length)
    }

    buffer.flush()
    buffer.toByteArray
  }

  def readFully(is: => InputStream) =
    Task {
      \/.fromTryCatchNonFatal {
        val is0 = is
        val b =
          try readFullySync(is0)
          finally is0.close()

        new String(b, "UTF-8")
      } .leftMap{
        case e: java.io.FileNotFoundException =>
          s"Not found: ${e.getMessage}"
        case e =>
          s"$e: ${e.getMessage}"
      }
    }

}
