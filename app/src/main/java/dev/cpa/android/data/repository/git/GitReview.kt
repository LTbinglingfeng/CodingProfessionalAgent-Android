package dev.cpa.android.data.repository.git

data class GitResult(val exitCode: Int, val stdout: String)
data class GitRepo(val root: String, val current: String?, val defaultBranch: String, val branches: List<String>, val remote: String?, val clean: Boolean)
data class GitReview(val repo: GitRepo, val files: List<GitFile>, val comparison: String, val workingTree: Boolean = false)
class GitReviewLoader(private val run: suspend (List<String>, String) -> GitResult) {
    suspend fun load(cwd: String, base: String? = null, target: String? = null): GitReview {
        suspend fun git(vararg args: String) = run(args.toList(), cwd)
        suspend fun text(vararg args: String) = git(*args).takeIf { it.exitCode == 0 }?.stdout?.trim()?.takeIf(String::isNotEmpty)
        val root = text("rev-parse", "--show-toplevel") ?: error("不是Git仓库或无法读取仓库")
        val current = text("branch", "--show-current")
        val originHead = text("symbolic-ref", "refs/remotes/origin/HEAD")?.substringAfter("refs/remotes/origin/", "")?.takeIf(String::isNotEmpty)
        val defaultBranch = originHead ?: text("branch", "-a", "--format=%(refname)").let { output ->
            val refs = output.orEmpty().lines().toSet()
            listOf("main", "master").firstOrNull { "refs/heads/$it" in refs || "refs/remotes/origin/$it" in refs } ?: "main"
        }
        val branches = text("for-each-ref", "--format=%(refname:short)", "refs/heads/")?.lines()?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val remote = text("config", "--get", "remote.origin.url")
        val status = git("status", "--porcelain", "-uall", "-z")
        check(status.exitCode == 0) { "无法读取Git状态" }
        val repo = GitRepo(root, current, defaultBranch, branches, remote, status.stdout.isEmpty())
        if (base != null) {
            val comparison = "origin/$base...${target?.takeIf(String::isNotBlank) ?: current ?: "HEAD"}"
            val result = git("diff", comparison)
            check(result.exitCode == 0) { "无法读取分支差异，请检查远程基准分支" }
            return GitReview(repo, GitDiffParser.parse(result.stdout), comparison)
        }
        val diff = git("diff", "HEAD")
        val hasHead = git("rev-parse", "--verify", "HEAD").exitCode == 0
        check(diff.exitCode == 0 || !hasHead) { "无法读取工作区差异" }
        val files = GitDiffParser.parse(if (diff.exitCode == 0) diff.stdout else "").toMutableList()
        val records = status.stdout.split('\u0000'); var i = 0
        while (i < records.size) {
            val record = records[i++]; if (record.length < 4) continue
            val code = record.take(2); val path = record.drop(3)
            // Porcelain -z rename/copy records have a second source-path field, not another status.
            if (code.any { it == 'R' || it == 'C' }) i++
            val untracked = code == "??"
            if ((!untracked && hasHead) || path.isEmpty() || files.any { it.path == path }) continue
            val result = run(listOf("diff", "--no-index", "--", "/dev/null", path), root)
            check(result.exitCode in 0..1) { "无法读取文件差异: $path" }
            val parsed = GitDiffParser.parse(result.stdout).firstOrNull()
            if (parsed != null) files += parsed.copy(oldPath = path, path = path, status = if (untracked) "untracked" else "added")
            else if (untracked) files += GitFile(path, path, "untracked", false, emptyList())
        }
        return GitReview(repo, files, "HEAD", workingTree = true)
    }
}
