package jbok.common
import better.files.File
import cats.effect.IO
import jbok.JbokSpec

class FileLockSpec extends JbokSpec {
  "FileLock" should {
    "release lock whatever use" in {
      val file = File.newTemporaryFile()
      val p = FileLock
        .lock[IO](file.path)
        .use { _ =>
          IO.raiseError(new Exception("boom"))
        }
        .attempt
      p.unsafeRunSync()
      file.exists shouldBe false
    }

    "raise FileLockErr if already locked" in {
      val file = File.newTemporaryFile()
      val p = FileLock
        .lock[IO](file.path)
        .use { _ =>
          FileLock
            .lock[IO](file.path)
            .use { _ =>
              IO.unit
            }
            .attempt
            .map(x => x.left.get shouldBe FileLockErr(file.path))
        }
        .attempt
      p.unsafeRunSync().isRight shouldBe true
      file.exists shouldBe false
    }

    "lock with content" in {
      val file = File.newTemporaryFile()
      val p = FileLock
        .lock[IO](file.path, "oho")
        .use { _ =>
          IO(println(file.lines.head)).flatMap(_ => IO.raiseError(new Exception("boom")))
        }
        .attempt
      p.unsafeRunSync()
      file.exists shouldBe false
    }
  }
}
