package com.example.notionreader.discovery

import androidx.test.platform.app.InstrumentationRegistry
import com.example.notionreader.application.DiscoveryController
import com.example.notionreader.data.discovery.OnnxEmbeddingProvider
import com.example.notionreader.data.local.FileDiscoveryInterestStore
import com.example.notionreader.data.local.FileDocumentCache
import com.example.notionreader.data.local.FileSemanticIndexStore
import com.example.notionreader.domain.ReaderDocument
import com.example.notionreader.domain.ReaderIndexEntry
import com.example.notionreader.domain.discovery.*
import com.example.notionreader.domain.document.CachedDocument
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

/** Historical MiniLM baseline, despite the retained runner class name. Not active Gemma qualification.
 * Explicit fixture: tools/semantic-discovery/prepare-device.ps1. Never skips.
 * Active model checks: GemmaDeviceIntegrationTest and GemmaDiscoveryJourneyTest.
 */
class ShippingDiscoveryInferenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun asset(name: String) = instrumentation.context.assets.open("discovery/$name").bufferedReader().use { it.readText() }
    internal fun provider(): OnnxEmbeddingProvider {
        val directory = File(context.noBackupFilesDir,"discovery-probe-model").apply { mkdirs() }
        val artifacts=mapOf(
            "model_qint8_arm64.onnx" to "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474",
            "vocab.txt" to "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"
        )
        for((name,expected) in artifacts) {
            val descriptor=instrumentation.uiAutomation.executeShellCommand("run-as com.example.notionreader cp /data/local/tmp/discovery-$name no_backup/discovery-probe-model/$name")
            val output=android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            assertTrue("Stage public artifacts with tools/semantic-discovery/prepare-device.ps1; copying $name failed: $output",output.isBlank())
            val file=File(directory,name)
            assertTrue("Missing copied artifact $name",file.isFile)
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(65536);var length=input.read(buffer);while(length!=-1) {digest.update(buffer,0,length);length=input.read(buffer)} }
            assertEquals("Copied artifact checksum $name",expected,digest.digest().joinToString("") { "%02x".format(it) })
        }
        return OnnxEmbeddingProvider(directory)
    }

    @Test fun shippingProviderPreservesPublisherTokensAndHeldOutSemanticQuality() = runBlocking {
        val provider = provider()
        val root=File(context.noBackupFilesDir,"discovery-quality-${System.nanoTime()}").apply { mkdirs() }
        try {
            val goldens=org.json.JSONArray(asset("tokenizer-golden.json"))
            for(i in 0 until goldens.length()) {
                val item=goldens.getJSONObject(i); val ids=item.getJSONArray("input_ids")
                assertEquals((1 until ids.length()-1).map { ids.getInt(it) },provider.tokenizer.tokens(item.getString("text")).map { it.id })
            }
            val corpus=JSONObject(asset("evaluation.json"));val docs=corpus.getJSONArray("documents");val queries=corpus.getJSONArray("queries")
            val started=System.nanoTime()
            val cache=FileDocumentCache(File(root,"cache"));val index=FileSemanticIndexStore(File(root,"index"))
            val entries=(0 until docs.length()).map { d ->
                val item=docs.getJSONObject(d);val id=item.getString("id")
                val document=ReaderDocument.fromChunkTexts(id,item.getString("title"),item.getString("text").split("\n\n"))
                cache.put(CachedDocument(document,null,fetchedAt=Instant.EPOCH))
                ReaderIndexEntry(id,document.title,null,area=if(id in listOf("database","cache","encryption")) "Computing" else "General")
            }
            index.setPaused(false)
            val controller=DiscoveryController(cache,index,FileDiscoveryInterestStore(File(root,"interests")),provider,recommendationThreshold=OnnxEmbeddingProvider.RECOMMENDATION_THRESHOLD)
            controller.reconcile(entries,true)
            assertEquals(docs.length(),controller.state.value.indexedCount)
            val vectors=index.pageIds().flatMap { index.read(it)!!.passages.map { passage ->passage.vector } }
            vectors.forEach { vector -> assertEquals(384,vector.size);assertTrue(vector.all { it.isFinite() });assertEquals(1.0,sqrt(vector.sumOf { it.toDouble()*it }),.00001) }
            val ranked=(0 until queries.length()).map { q ->
                controller.search(queries.getJSONObject(q).getString("text"))
                assertNull(controller.state.value.message)
                controller.state.value.results.map { match -> (0 until docs.length()).first { canonicalSemanticId(docs.getJSONObject(it).getString("id"))==match.pageId } to match.score }
            }
            fun relevant(q:Int,d:Int)= (0 until queries.getJSONObject(q).getJSONArray("relevant").length()).any { queries.getJSONObject(q).getJSONArray("relevant").getString(it)==docs.getJSONObject(d).getString("id") }
            val calibration=(0 until queries.length()).filter { queries.getJSONObject(it).getString("split")=="calibration" }
            fun precision(indices:List<Int>,threshold:Float):Double { val selected=indices.flatMap { q -> ranked[q].take(5).filter { it.second>=threshold }.map { relevant(q,it.first) } };return if(selected.isEmpty()) 0.0 else selected.count { it }.toDouble()/selected.size }
            val threshold=calibration.flatMap { ranked[it].map { it.second } }.distinct().sorted().first { precision(calibration,it)>=.8 }
            val held=(0 until queries.length()).filter { queries.getJSONObject(it).getString("split")=="held-out" }
            val recall=held.map { q ->ranked[q].take(5).count { relevant(q,it.first) }.toDouble()/queries.getJSONObject(q).getJSONArray("relevant").length() }.average()
            val ndcg=held.map { q -> val ideal=(0 until minOf(5,queries.getJSONObject(q).getJSONArray("relevant").length())).sumOf {1.0/(ln((it+2).toDouble())/ln(2.0))};ranked[q].take(5).mapIndexed { rank,match -> if(relevant(q,match.first)) 1.0/(ln((rank+2).toDouble())/ln(2.0)) else 0.0 }.sum()/ideal }.average()
            // Exact production Browse baseline searches metadata/title, not document body.
            val keywordRecall=held.map { q -> val ids=BrowseQuery(text=queries.getJSONObject(q).getString("text")).applyTo(entries).entries.take(5).map { it.pageId };ids.count { id -> relevant(q,(0 until docs.length()).first { docs.getJSONObject(it).getString("id")==id }) }.toDouble()/queries.getJSONObject(q).getJSONArray("relevant").length() }.average()
            val fixedThreshold=OnnxEmbeddingProvider.RECOMMENDATION_THRESHOLD
            val report=JSONObject().put("pipeline","shipping cache->chunker->provider->index->controller").put("calibrationThreshold",threshold).put("calibrationPrecision",precision(calibration,threshold)).put("fixedProductionThreshold",fixedThreshold).put("fixedThresholdCalibrationPrecision",precision(calibration,fixedThreshold)).put("fixedThresholdHeldOutPrecision",precision(held,fixedThreshold)).put("heldOutPrecision",precision(held,threshold)).put("heldOutRecallAt5",recall).put("heldOutNdcgAt5",ndcg).put("productionBrowseKeywordRecallAt5",keywordRecall).put("elapsedMs",(System.nanoTime()-started)/1e6)
            File(context.noBackupFilesDir,"discovery-shipping-quality.json").writeText(report.toString(2))
            android.util.Log.i("DiscoveryShipping","quality=$report")
            assertTrue("Held-out Recall@5: $report",recall>=.8);assertTrue("Held-out precision: $report",precision(held,threshold)>=.8)
            assertTrue("Fixed production threshold calibration precision: $report",precision(calibration,fixedThreshold)>=.8)
            assertTrue("Fixed production threshold held-out precision: $report",precision(held,fixedThreshold)>=.8)
            controller.relatedNotes("database")
            assertTrue(controller.state.value.related.none { it.pageId=="database" });assertTrue(controller.state.value.related.size<=5)
            controller.search("database",BrowseQuery(selectedArea="Computing"))
            assertTrue(controller.state.value.results.isNotEmpty());assertTrue(controller.state.value.results.all { it.metadata.area=="Computing" })
            val before=provider.embed("A reproducible normalized embedding")
            provider.release();assertEquals(before,provider.embed("A reproducible normalized embedding"))
        } finally { provider.release();root.deleteRecursively() }
    }

    @Test fun shippingLongDocumentAndTenThousandDiskPassagesMeetTheQueryTarget() = runBlocking {
        val root=File(context.noBackupFilesDir,"discovery-benchmark-${System.nanoTime()}").apply { mkdirs() }
        val provider=provider()
        try {
            val text="Database transactions protect atomic writes and recover safely after interruption."
            val template=ReaderDocument.fromChunkTexts("template","Database reliability",List(100) { text })
            val chunked=SemanticChunker(provider.tokenizer).chunk(template)
            assertEquals(100,chunked.size)
            val index=FileSemanticIndexStore(File(root,"index"));val cache=FileDocumentCache(File(root,"cache"))
            val interests=FileDiscoveryInterestStore(File(root,"interests"))
            val started=System.nanoTime()
            val embedded=chunked.map { EmbeddedPassage(it,provider.embed(it.input)) }
            val indexingMs=(System.nanoTime()-started)/1e6
            android.util.Log.i("DiscoveryShipping","phase=embedded100passages elapsedMs=$indexingMs")
            assertTrue(embedded.all { provider.tokenizer.tokens(it.passage.input).size+2<=256 })
            for(note in 0 until 100) {
                val id="benchmark$note";val document=template.copy(pageId=id)
                cache.put(CachedDocument(document,null,fetchedAt=Instant.EPOCH))
                val passages=embedded.map { it.copy(passage=it.passage.copy(pageId=id,id="$id:${it.passage.order}")) }
                assertTrue("10,000 passages exceed shipping64MiB budget",index.write(SemanticRecord(id,semanticRevision(document),provider.identity,SemanticMetadata(document.title),passages)))
            }
            val ids=index.pageIds();assertEquals(100,ids.size)
            assertEquals(10000,ids.sumOf { index.read(it)!!.passages.size })
            android.util.Log.i("DiscoveryShipping","phase=verified10000diskPassages indexBytes=${index.bytes()}")
            val controller=DiscoveryController(cache,index,interests,provider, queryTimings = { timings ->
                android.util.Log.i("DiscoveryShipping", "queryPhasesMs=" + timings.mapValues { it.value / 1_000_000.0 })
            })
            val warmupStarted=System.nanoTime()
            controller.search("How do databases keep changes safe?")
            android.util.Log.i("DiscoveryShipping","phase=warmup elapsedMs=${(System.nanoTime()-warmupStarted)/1e6}")
            val samples=List(5) {
                val start=System.nanoTime();controller.search("How do databases keep changes safe?");val elapsed=(System.nanoTime()-start)/1e6
                android.util.Log.i("DiscoveryShipping","phase=timedQuery sample=$it elapsedMs=$elapsed")
                assertEquals(20,controller.state.value.results.size);assertNull(controller.state.value.message)
                // For five nearest-rank samples p95 is the maximum: any breach already proves failure.
                assertTrue("A timed query already exceeds the unchanged2second p95 target: $elapsed ms",elapsed<2000)
                elapsed
            }.sorted()
            val memory=android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
            val report=JSONObject().put("passages",10000).put("notes",100).put("syntheticRepeatedEmbedding",true).put("indexBytes",index.bytes()).put("hundredPassageIndexMs",indexingMs).put("queryP50Ms",samples[2]).put("queryP95Ms",samples[4]).put("nativeHeapBytes",android.os.Debug.getNativeHeapAllocatedSize()).put("totalPssKiB",memory.totalPss)
            File(context.noBackupFilesDir,"discovery-shipping-benchmark.json").writeText(report.toString(2))
            android.util.Log.i("DiscoveryShipping","benchmark=$report")
            assertTrue("Shipping10k disk query p95 must be under2seconds: $report",samples[4]<2000)
        } finally { provider.release();root.deleteRecursively() }
    }
}
