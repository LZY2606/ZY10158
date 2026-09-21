package app

import java.io.File
import kotlin.system.exitProcess

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        var port = 5358
        var dbPath = "data/cantp.sqlite"
        var fixtureDir: String? = "fixtures"

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--port" -> { port = args[++i].toInt() }
                "--db" -> { dbPath = args[++i] }
                "--no-fixtures" -> { fixtureDir = null }
                "--fixtures" -> { fixtureDir = args[++i] }
                "--help", "-h" -> {
                    println("用法: MainKt [--port 5358] [--db data/cantp.sqlite] [--fixtures fixtures|--no-fixtures]")
                    exitProcess(0)
                }
                else -> System.err.println("忽略未知参数: ${args[i]}")
            }
            i++
        }

        val database = Database(dbPath)
        val repo = Repository(database)
        val service = ViewService(repo)
        val server = WebServer(port, service, repo)

        // 首次启动自动导入内置 fixture（按文件哈希幂等，重复启动不会产生重复数据）
        if (fixtureDir != null) {
            val dir = File(fixtureDir)
            if (dir.isDirectory) {
                dir.listFiles { f -> f.isFile && f.extension in setOf("log", "can", "txt") }
                    ?.sortedBy { it.name }
                    ?.forEach { f ->
                        val existed = repo.listCaptures().any { it.filename == f.name }
                        if (!existed) {
                            val res = repo.importCapture(f.name, f.readText())
                            service.recompute(res.captureId)
                            println("已导入 fixture: ${f.name} (captureId=${res.captureId})")
                        }
                    }
            }
        }

        server.start()
        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop()
            database.close()
        })
    }
}

fun main(args: Array<String>) = Main.main(args)
