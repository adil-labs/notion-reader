package com.example.notionreader.architecture

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class BackupPolicyTest {
    private val module = File(requireNotNull(System.getProperty("user.dir"))).let { if (File(it, "src/main").exists()) it else File(it, "app") }
    private val portable = setOf("datastore/user_settings.preferences_pb", "datastore/listening_history.preferences_pb",
        "datastore/saved_queues.preferences_pb", "datastore/smart_queues.preferences_pb")
    private fun policies(): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val old = factory.newDocumentBuilder().parse(File(module, "src/main/res/xml/backup_rules.xml")).documentElement
        val modern = factory.newDocumentBuilder().parse(File(module, "src/main/res/xml/data_extraction_rules.xml")).documentElement
        return listOf(old, modern.getElementsByTagName("cloud-backup").item(0) as Element,
            modern.getElementsByTagName("device-transfer").item(0) as Element)
    }
    private fun rules(policy: Element, tag: String): List<Element> = policy.getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }
    @Test fun backupsPreservePortableUserStateWithoutIncludingDerivedData() {
        for (policy in policies()) {
            val included = rules(policy, "include")
            assertEquals(portable, included.map { it.getAttribute("path") }.toSet())
            assertTrue(included.all { it.getAttribute("domain") == "file" })
            for (excluded in rules(policy, "exclude").filter { it.getAttribute("domain") == "file" }) {
                val prefix = excluded.getAttribute("path").trimEnd('/')
                assertTrue("An exclusion cannot erase included portable state", portable.none { it == prefix || it.startsWith("$prefix/") || prefix == "." })
            }
        }
    }
    @Test fun backupRulesUseAndroidSupportedDomains() {
        val supported = setOf("file", "database", "sharedpref", "external", "root", "device_file", "device_database", "device_sharedpref", "device_root")
        for (policy in policies()) for (rule in rules(policy, "include") + rules(policy, "exclude"))
            assertTrue("Unsupported backup domain: ${rule.getAttribute("domain")}", rule.getAttribute("domain") in supported)
    }
}
