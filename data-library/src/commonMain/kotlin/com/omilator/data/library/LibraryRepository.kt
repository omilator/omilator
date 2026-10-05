package com.omilator.data.library

interface LibraryScanner {
    suspend fun scan(directory: String): List<Game>
}

class LibraryRepository(
    private val scanner: LibraryScanner,
) {
    private var cached: List<Game> = emptyList()

    /** Scans every configured directory into one cache. The old
     *  single-directory rescan overwrote the cache once per directory, so
     *  repository-level queries only ever saw the last one. */
    suspend fun rescan(directories: List<String>): List<Game> {
        cached = directories
            .flatMap { scanner.scan(it) }
            .distinctBy { it.id }
        return cached
    }

    fun games(): List<Game> = cached

    fun gamesBySystem(system: GameSystem): List<Game> =
        cached.filter { it.system == system }

    fun search(query: String): List<Game> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return cached
        return cached.filter { it.title.lowercase().contains(q) }
    }
}
