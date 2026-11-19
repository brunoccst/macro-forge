package macroforge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A broken file (macro, template library, data) fails on its own: everything else keeps loading. */
class LoadIsolationTest {
    private lateinit var root: File

    // the kind of quoting mistake that once broke every connector call
    private val brokenYaml = "name: bad\nnotes: '\"Confirming Updates\" under the title screen's progress bar'\n"

    @Before fun setUp() {
        root = Files.createTempDirectory("library").toFile()
        LoadProblems.reset()
    }

    @After fun tearDown() { root.deleteRecursively(); LoadProblems.reset() }

    private fun write(path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun aBrokenMacroIsSkippedAndTheOthersLoad() {
        write("macros/daily/good.yaml", "name: good\nscreens: [field]\nhandlers:\n  field: [{tap: [0.5, 0.5]}]\n")
        write("macros/daily/bad.yaml", brokenYaml)
        val macros = Scenarios.parseAll(LibraryFiles(root))
        assertEquals(listOf("good"), macros.map { it.name })
        val p = LoadProblems.forMacro("bad")
        assertNotNull(p)
        assertEquals("macros/daily/bad.yaml", p!!.file)
        assertTrue(Scenarios.missing("bad"), Scenarios.missing("bad").contains("can't be loaded"))
        assertEquals("unknown macro 'nothing'", Scenarios.missing("nothing"))
    }

    @Test fun aFixedMacroIsNoLongerListed() {
        write("macros/daily/bad.yaml", brokenYaml)
        Scenarios.parseAll(LibraryFiles(root))
        assertNotNull(LoadProblems.forMacro("bad"))
        write("macros/daily/bad.yaml", "name: bad\nscreens: [field]\nhandlers:\n  field: [{tap: [0.5, 0.5]}]\n")
        Scenarios.parseAll(LibraryFiles(root))
        assertNull(LoadProblems.forMacro("bad"))
    }

    @Test fun aBrokenTemplateLibraryOnlyLosesItsOwnTemplates() {
        write("templates/common/library.yaml", "templates:\n  ok_button: {region: [0, 0, 1, 1]}\nscreens:\n  field: {require: [ok_button]}\n")
        write("templates/broken/library.yaml", "templates:\n  x:\n    notes: '\"a\" b's c'\n")
        val lib = Library.load(LibraryFiles(root), "templates/common", "templates/broken")
        assertTrue("ok_button" in lib.templates)
        assertTrue("field" in lib.screens)
        assertEquals(listOf("templates/broken/library.yaml"), LoadProblems.broken().map { it.file })
    }

    @Test fun aBrokenEntryOnlyLosesItself() {
        write("templates/common/library.yaml", "templates:\n  good: {region: [0, 0, 1, 1]}\n  bad: {region: [0, 0]}\n")
        val lib = Library.load(LibraryFiles(root), "templates/common")
        assertTrue("good" in lib.templates)
        assertTrue("bad" !in lib.templates)
        assertTrue(LoadProblems.broken().single().message.contains("template bad"))
    }

    @Test fun everyYamlFileOfAPackIsChecked() {
        write("game.yaml", "name: Starter\n")
        write("macros/daily/good.yaml", "name: good\n")
        write("templates/maps/routes.yaml", "a: 'it's'\n")
        write("templates/maps/library.yaml", "templates: {}\n")
        val bad = MacroLibrary.checkFiles(LibraryFiles(root))
        assertEquals(listOf("templates/maps/routes.yaml"), bad.map { it.file })
        assertEquals("data", bad.single().kind)
    }
}
