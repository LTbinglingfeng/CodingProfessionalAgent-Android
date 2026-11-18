package dev.cpa.android.data.repository.git

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GitDiffTest {
    private val diff = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1 +1,2 @@ heading\n-old\n+new\n+extra\n\\ No newline at end of file\n"
    @Test fun countsLinesAndStopsAtDeclaredHunkEnd() {
        val file = GitDiffParser.parse(diff + "trailer\n").single()
        assertEquals(2, file.additions); assertEquals(1, file.deletions)
        assertEquals(listOf(null, 1, 2), file.hunks.single().lines.map { it.newNumber })
        assertEquals(listOf(1, null, null), file.hunks.single().lines.map { it.oldNumber })
    }
    @Test fun handlesCrLfSpacesAndDeletedPathFallback() {
        val file = GitDiffParser.parse("diff --git a/a file b/a file\r\ndeleted file mode 100644\r\n--- a/a file\r\n+++ /dev/null\r\n@@ -1 +0,0 @@\r\n-gone\r\n").single()
        assertEquals("a file", file.path); assertEquals("deleted", file.status); assertEquals("gone", file.hunks.single().lines.single().text)
    }
    @Test fun renameToRelativeDevNullKeepsDestination() {
        val file = GitDiffParser.parse("diff --git a/old b/dev/null\nrename from old\nrename to dev/null\n").single()
        assertEquals("dev/null", file.path)
    }
    @Test fun decodesQuotedUtf8AndPreservesUnknownEscapes() {
        assertEquals("你好.txt", GitDiffParser.unquote("\"\\344\\275\\240\\345\\245\\275.txt\""))
        assertEquals("a\tb\\q", GitDiffParser.unquote("\"a\\tb\\q\""))
    }
    @Test fun binaryRenameAndCopyHaveNoSyntheticLines() {
        val files = GitDiffParser.parse("diff --git a/old b/new\nrename from old name\nrename to new name\nBinary files a/old and b/new differ\ndiff --git a/a b/b\ncopy from original\ncopy to copy\n")
        assertEquals("new name", files[0].path); assertTrue(files[0].binary); assertEquals("renamed", files[0].status)
        assertEquals("copy", files[1].path); assertEquals("copied", files[1].status); assertEquals(0, files[1].additions)
    }
    @Test fun malformedInputDoesNotInventFilesOrCrash() {
        assertTrue(GitDiffParser.parse("not a diff").isEmpty())
        assertTrue(GitDiffParser.parse("diff --git \"unterminated\n--- \n+++ \n@@ invalid\n").single().hunks.isEmpty())
    }
    private fun base(args: List<String>): GitResult = when(args.first()) {
        "rev-parse" -> if (args.contains("--show-toplevel")) GitResult(0, "/repo\n") else GitResult(0, "hash")
        "branch" -> GitResult(0, "main")
        "symbolic-ref" -> GitResult(0, "refs/remotes/origin/main")
        "for-each-ref" -> GitResult(0, "main\nfeature")
        "config" -> GitResult(1, "")
        "status" -> GitResult(0, "")
        else -> GitResult(0, "")
    }
    @Test fun untrackedNoIndexUsesRepoRootAndAcceptsExitOne() = runTest {
        val calls = mutableListOf<Pair<List<String>, String>>()
        val loader = GitReviewLoader { args, cwd -> calls += args to cwd
            when { args.first() == "status" -> GitResult(0, "?? new file\u0000")
                args.contains("--no-index") -> GitResult(1, diff)
                else -> base(args) }
        }
        val result = loader.load("/repo/sub")
        assertTrue(result.workingTree); assertEquals("HEAD", result.comparison)
        assertEquals("new file", result.files.single().path); assertEquals("untracked", result.files.single().status)
        assertEquals("/repo", calls.last().second); assertEquals(listOf("diff", "--no-index", "--", "/dev/null", "new file"), calls.last().first)
    }
    @Test fun unbornHeadIncludesStagedFilesAndEmptyUntrackedFallback() = runTest {
        val loader = GitReviewLoader { args, _ -> when {
            args.contains("--verify") || args == listOf("diff", "HEAD") -> GitResult(128, "")
            args.first() == "status" -> GitResult(0, "A  staged\u0000?? empty\u0000")
            args.last() == "staged" -> GitResult(1, diff)
            else -> base(args)
        } }
        val result = loader.load("/repo")
        assertEquals(listOf("added", "untracked"), result.files.map { it.status })
        assertEquals(0, result.files.last().additions)
    }
    @Test fun failedUntrackedDiffIsNotReportedAsAnEmptyFile() = runTest {
        val loader = GitReviewLoader { args, _ -> when {
            args.first() == "status" -> GitResult(0, "?? missing\u0000")
            args.contains("--no-index") -> GitResult(128, "")
            else -> base(args)
        } }
        val error = runCatching { loader.load("/repo") }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error.message.orEmpty().contains("missing"))
    }
    @Test fun fallbackDefaultBranchMatchesExactLocalOrOriginRefs() = runTest {
        for ((refs, expected) in listOf(
            "refs/heads/maintenance\nrefs/remotes/origin/master" to "master",
            "refs/heads/feature/main\nrefs/heads/master" to "master",
            "refs/heads/masterpiece" to "main",
            "refs/heads/master\nrefs/remotes/origin/main" to "main",
        )) {
            val loader = GitReviewLoader { args, _ -> when {
                args.first() == "symbolic-ref" -> GitResult(1, "")
                args.first() == "branch" && "-a" in args -> GitResult(0, refs)
                else -> base(args)
            } }
            assertEquals(expected, loader.load("/repo").repo.defaultBranch)
        }
    }
    @Test fun branchComparisonNeverFallsBackToLocalBase() = runTest {
        val calls = mutableListOf<List<String>>()
        val loader = GitReviewLoader { args, _ -> calls += args; if (args.first() == "diff") GitResult(128, "") else base(args) }
        assertTrue(runCatching { loader.load("/repo", "main", "feature") }.isFailure)
        assertEquals(listOf(listOf("diff", "origin/main...feature")), calls.filter { it.first() == "diff" })
    }
    @Test fun failedStatusIsNotAClaimOfCleanRepository() = runTest {
        val loader = GitReviewLoader { args, _ -> if (args.first() == "status") GitResult(1, "") else base(args) }
        assertTrue(runCatching { loader.load("/repo") }.isFailure)
    }
}
