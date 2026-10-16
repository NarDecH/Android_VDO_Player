package com.nardech.vdoplayer

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The app promises a Thai translation that is complete, so the two string files are
 * compared directly: same keys, same placeholders, and Thai text that is really Thai.
 *
 * Reading the resource files instead of a running `Resources` object keeps this a plain
 * unit test that runs anywhere, including on a machine with no Android SDK bits.
 */
class LocalizationTest {

    private val english = ResourceStrings("values")
    private val thai = ResourceStrings("values-th")

    @Test
    fun `both locales define exactly the same keys`() {
        val onlyInEnglish = (english.keys - thai.keys).sorted()
        val onlyInThai = (thai.keys - english.keys).sorted()

        assertEquals("missing from values-th/strings.xml", emptyList<String>(), onlyInEnglish)
        assertEquals("unknown key in values-th/strings.xml", emptyList<String>(), onlyInThai)
    }

    @Test
    fun `no key is defined twice`() {
        assertEquals(emptyList<String>(), english.duplicates)
        assertEquals(emptyList<String>(), thai.duplicates)
    }

    @Test
    fun `thai plurals define every quantity thai needs`() {
        assertEquals(english.pluralQuantities.keys, thai.pluralQuantities.keys)

        // Thai has no singular form in CLDR, so values-th defines "other" only and must
        // not invent a quantity Android will never ask for.
        thai.pluralQuantities.forEach { (key, quantities) ->
            assertTrue("'$key' has no 'other' form, which every locale needs", "other" in quantities)
            assertTrue(
                "'$key' defines a quantity Thai never uses: $quantities",
                quantities.all { it in english.pluralQuantities.getValue(key) },
            )
        }
        english.pluralQuantities.forEach { (key, quantities) ->
            assertTrue("'$key' lost its singular form: $quantities", "one" in quantities)
        }
    }

    @Test
    fun `placeholders survive translation`() {
        // Only quantities both files define are compared: see the plural test above for
        // why values-th legitimately has fewer of them.
        val mismatched = english.values.keys.filter { key ->
            val translation = thai.values[key] ?: return@filter false
            placeholders(english.values.getValue(key)) != placeholders(translation)
        }

        assertEquals("these translations lost a %1\$s style placeholder", emptyList<String>(), mismatched)
    }

    @Test
    fun `thai values are translated instead of copied from english`() {
        val untranslated = thai.values
            .filterKeys { it !in LANGUAGE_NEUTRAL }
            .filterKeys { it in english.values }
            .filter { (key, value) ->
                val source = english.values[key].orEmpty()
                source.any { it in 'a'..'z' || it in 'A'..'Z' } && !value.any { it.isThai() }
            }
            .keys
            .sorted()

        assertEquals("these strings are still English", emptyList<String>(), untranslated)
    }

    @Test
    fun `the transfer screen is translated`() {
        val transferKeys = english.keys.filter { it.startsWith("transfer_") || it == "tab_transfer" }

        assertTrue("the transfer feature looks untranslated", transferKeys.size >= 40)
        transferKeys.forEach { key ->
            assertTrue("'$key' has no Thai value", thai.strings.containsKey(key))
            assertTrue(
                "'${thai.strings[key]}' looks like it was left in English",
                key == "transfer_progress" || thai.strings.getValue(key).any { it.isThai() },
            )
        }
    }

    @Test
    fun `thai is offered in the per-app language picker`() {
        val config = parse(resourceFile("xml", "locales_config.xml"))
        val locales = config.children("locale").map { element -> element.attr("name") }

        assertTrue("locales_config.xml does not list Thai: $locales", locales.contains("th"))
    }

    private fun Char.isThai(): Boolean = this in '\u0E00'..'\u0E7F'

    private companion object {

        /**
         * Values that read the same in every language: the app name, a URI label, and
         * strings made of nothing but placeholders and separators.
         */
        val LANGUAGE_NEUTRAL = setOf(
            "app_name",
            "details_uri",
            "library_meta",
            "player_queued_counter",
            "dialog_selected",
            "transfer_progress",
            // A product name: NextLib's decoder is FFmpeg in every language.
            "decoder_chip_ffmpeg",
        )

        val PLACEHOLDER = Regex("%(?:\\d+\\$)?[a-zA-Z]")

        fun placeholders(value: String): List<String> =
            PLACEHOLDER.findAll(value).map { it.value }.toList().sorted()
    }
}

/** One `strings.xml`, flattened so the two locales can be compared key for key. */
private class ResourceStrings(folder: String) {

    private val root = parse(resourceFile(folder, "strings.xml"))
    private val stringElements = root.children("string")
    private val pluralElements = root.children("plurals")

    private val names = stringElements.map { it.attr("name") } + pluralElements.map { it.attr("name") }

    val duplicates: List<String> =
        names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

    val keys: Set<String> = names.toSet()

    val strings: Map<String, String> =
        stringElements.associate { it.attr("name") to it.textContent }

    val pluralQuantities: Map<String, Set<String>> = pluralElements.associate { plural ->
        plural.attr("name") to plural.children("item").map { it.attr("quantity") }.toSet()
    }

    /** Every translatable value; plurals are flattened to `key#quantity`. */
    val values: Map<String, String> = strings + pluralElements.flatMap { plural ->
        plural.children("item").map { item ->
            "${plural.attr("name")}#${item.attr("quantity")}" to item.textContent
        }
    }
}

private fun parse(file: File): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        // Resource XML never needs a DTD, and loading one is a needless risk.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
        // locales_config.xml uses the `android:` prefix, so attributes must be read by
        // local name rather than by the qualified one.
        isNamespaceAware = true
    }
    return factory.newDocumentBuilder().parse(file).documentElement
}

/** Reads `name="..."` whether or not the attribute carries a namespace prefix. */
private fun Element.attr(local: String): String {
    val attributes = attributes
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        if ((attribute.localName ?: attribute.nodeName) == local) {
            return attribute.nodeValue ?: ""
        }
    }
    return ""
}

private fun Element.children(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

/**
 * Unit tests run from either the module or the root project depending on the Gradle
 * version, so try both before giving up.
 */
private fun resourceFile(folder: String, name: String): File {
    val relative = "src/main/res/$folder/$name"
    val candidates = listOf(File(relative), File("app/$relative"))
    return candidates.firstOrNull { it.isFile }
        ?: throw AssertionError(
            "Could not find $relative from ${File("").absolutePath}",
        )
}
