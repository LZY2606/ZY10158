package app

import java.io.File

/**
 * Entry point.
 *
 *   java -jar can-diag-1.0.0.jar --port 5358 [--db data/can-diag.sqlite] [--fixtures fixtures]
 *
 * The service only analyzes files in storage; it never opens a CAN interface and never
 * sends bus messages.
 */
fun main(args: Array<String>) {
    var port = 5358
    var dbPath = "data/can-diag.sqlite"
    var fixturesDir: String? = "fixtures"
    var host = "127.0.0.1"
    var manualClock = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> { port = args.getOrNull(++i)?.toIntOrNull() ?: error("--port needs a number") }
            "--db" -> { dbPath = args.getOrNull(++i) ?: error("--db needs a path") }
            "--fixtures" -> { fixturesDir = args.getOrNull(++i) }
            "--host" -> { host = args.getOrNull(++i) ?: error("--host needs a value") }
            "--manual-clock" -> { manualClock = true }
            "--no-fixtures" -> { fixturesDir = null }
            "--help", "-h" -> {
                println("Usage: can-diag [--port 5358] [--db data/can-diag.sqlite] [--fixtures fixtures|PATH] [--no-fixtures] [--manual-clock]")
                return
            }
            else -> error("unknown argument: ${args[i]}")
        }
        i++
    }

    File(dbPath).absoluteFile.parentFile?.mkdirs()
    val db = Database(dbPath)
    val repo = Repository(db)
    val service = AnalysisService(repo, if (manualClock) ManualClock(0L) else SystemVirtualClock())

    if (fixturesDir != null) {
        val dir = File(fixturesDir)
        if (dir.isDirectory) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".cand") }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    try {
                        service.import(f.name, f.readText(Charsets.UTF_8))
                    } catch (e: Exception) {
                        System.err.println("warning: could not import fixture ${f.name}: ${e.message}")
                    }
                }
        }
    }

    val web = WebServer(service, repo, port, host)
    web.start()
    println("CAN diagnostic analyzer listening on http://$host:${web.boundPort}")
    println("storage: ${File(dbPath).absolutePath}")
    println("press Ctrl+C to stop")

    Runtime.getRuntime().addShutdownHook(Thread {
        web.stop(1)
        db.close()
    })
    Thread.currentThread().join()
}
