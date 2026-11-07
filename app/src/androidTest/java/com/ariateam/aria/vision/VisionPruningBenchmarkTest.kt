package com.ariateam.aria.vision

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.ActivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ariateam.aria.speech.VoiceService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.zip.ZipInputStream

/** Pixel 3 only, isolated test-copy benchmark. Inputs are raw [0,1] RGB and all sessions require GPU FP32. */
@RunWith(AndroidJUnit4::class)
class VisionPruningBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val root get() = File(context.getExternalFilesDir(null), "pruning")
    private val arms = listOf("baseline", "pruned_20pct", "pruned_40pct", "production")
    private val inputFloats = VisionContract.INPUT_FLOATS
    private val outputFloats = VisionContract.OUTPUT_FLOATS
    private val pixels = VisionContract.INPUT_PIXELS
    private var wasVisionServiceRunning=false
    private var wasVoiceServiceRunning=false
    private fun serviceRunning(name:String):Boolean {
        val am=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(100).any{it.service.className==name}
    }
    @Before fun isolateServices() {
        require(Build.DEVICE.equals("blueline",ignoreCase=true) || Build.MODEL.contains("Pixel 3",ignoreCase=true)) { "This experiment requires Pixel 3 (blueline), got ${Build.MODEL}/${Build.DEVICE}" }
        wasVisionServiceRunning=serviceRunning(VisionForegroundService::class.java.name)
        wasVoiceServiceRunning=serviceRunning(VoiceService::class.java.name)
        if(wasVisionServiceRunning) context.stopService(Intent(context,VisionForegroundService::class.java))
        if(wasVoiceServiceRunning) context.stopService(Intent(context,VoiceService::class.java))
        SystemClock.sleep(500)
    }
    @After fun restoreServices() {
        if(wasVisionServiceRunning) runCatching { androidx.core.content.ContextCompat.startForegroundService(context,Intent(context,VisionForegroundService::class.java)) }
        if(wasVoiceServiceRunning) runCatching { context.startService(Intent(context,VoiceService::class.java)) }
        File(context.cacheDir,"pruning-production.tflite").delete()
    }

    private fun sha(file: File): String = file.inputStream().use { input ->
        val d=MessageDigest.getInstance("SHA-256"); val b=ByteArray(65536); while(true){val n=input.read(b); if(n<0)break; d.update(b,0,n)}
        d.digest().joinToString("") { "%02x".format(it) }
    }
    private fun temp(): JSONObject {
        val i=context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw=i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1) ?: -1
        val pm=context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val t=if(Build.VERSION.SDK_INT>=29) pm.currentThermalStatus else -1
        return JSONObject().put("thermalStatus",t).put("batteryTempC",if(raw>0)raw/10.0 else -1.0)
    }
    private fun stats(values: List<Double>): JSONObject {
        val s=values.sorted(); val mean=values.average(); val sd=kotlin.math.sqrt(values.map{(it-mean)*(it-mean)}.average())
        fun q(p:Double)=s[((s.size-1)*p).toInt()]
        return JSONObject().put("n",s.size).put("meanMs",mean).put("stddevMs",sd).put("p50Ms",q(.5)).put("p90Ms",q(.9)).put("p95Ms",q(.95)).put("samplesMs",JSONArray(values))
    }
    private fun loadFixture(): List<ByteArray> {
        val zip=File(root,"fixture.zip"); require(zip.isFile) { "Push fixture.zip to ${zip.absolutePath}" }
        val fixtureManifest=JSONObject(File(root,"fixture.json").readText())
        assertEquals("Host/device fixture hash",fixtureManifest.getString("zip_sha256"),sha(zip))
        assertEquals("Fixture shape",JSONArray(listOf(512,288,3)).toString(),fixtureManifest.getJSONArray("shape").toString())
        val records=ArrayList<ByteArray>()
        ZipInputStream(zip.inputStream().buffered()).use { z -> while(true){ val e=z.nextEntry ?: break; if(!e.isDirectory && e.name.endsWith(".bin")){ val o=ByteArrayOutputStream(); z.copyTo(o); records.add(o.toByteArray()) }; z.closeEntry() } }
        require(records.size==64) { "Expected exact 64 fixture records, received ${records.size}" }
        val expected=pixels*5
        records.forEach { require(it.size==expected) { "Fixture record size ${it.size}, expected $expected" } }
        return records
    }
    private fun inputBuffer(rgb: ByteArray): ByteBuffer {
        val b=ByteBuffer.allocateDirect(VisionContract.INPUT_BYTES).order(ByteOrder.nativeOrder()); val f=b.asFloatBuffer()
        for(x in rgb) f.put((x.toInt() and 255)/255.0f)
        b.rewind(); return b
    }

    @Test fun benchmarkLatencyFallbackAndProductionGpu() {
        require(root.isDirectory)
        val expected=JSONObject(File(root,"model-manifest.json").readText()).getJSONObject("models")
        val files=linkedMapOf<String,File>()
        arms.forEach { arm ->
            val f=if(arm=="production") File(context.cacheDir,"pruning-production.tflite") else File(root,"models/model_${arm}.tflite")
            if(arm=="production" && !f.exists()) context.assets.open(VisionContract.DEFAULT_ASSET_MODEL_PATH).use{src->f.outputStream().use(src::copyTo)}
            val item=expected.getJSONObject(arm)
            assertEquals("$arm hash",item.getString("sha256"),sha(f)); assertEquals("$arm bytes",item.getLong("bytes"),f.length())
            files[arm]=f
        }
        val report=JSONObject().put("device",JSONObject().put("model",Build.MODEL).put("product",Build.PRODUCT).put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT))
        val probes=JSONObject()
        arms.forEach { arm ->
            val seg=LiteRtVisionSegmenter(context=context,inferencePolicy=ProductionGpuOnlyPolicy)
            try {
                val result=seg.loadModelFile(files.getValue(arm)); assertTrue("$arm tensor contract: $result",result is VisionContract.SpecValidationResult.Valid)
                if(arm=="production") assertEquals(VisionBackendType.GPU,seg.activeBackendType) else assertEquals(VisionBackendType.CPU,seg.activeBackendType)
                probes.put(arm,JSONObject().put("activeBackend",seg.activeBackendType?.name).put("description",seg.activeBackendDescription).put("gpuFallbackClass",seg.gpuFallbackCause?.javaClass?.name).put("gpuFallbackMessage",seg.gpuFallbackCause?.message))
            } finally { seg.close() }
        }
        report.put("strictGpuCompileProbes",probes)
        val rng=Random(20261106L); val rgb=ByteArray(VisionContract.INPUT_BYTES/4); rng.nextBytes(rgb)
        val orders=listOf(listOf("baseline","pruned_20pct","pruned_40pct","production"),listOf("production","pruned_40pct","pruned_20pct","baseline"))
        val blocks=JSONArray()
        for(block in orders.indices) {
            orders[block].forEachIndexed { position,arm ->
                val seg=LiteRtVisionSegmenter(context=context,inferencePolicy=ProductionGpuOnlyPolicy)
                try {
                    val begin=SystemClock.elapsedRealtimeNanos(); val result=seg.loadModelFile(files.getValue(arm)); val init=(SystemClock.elapsedRealtimeNanos()-begin)/1e6
                    assertTrue("$arm timing tensor contract: $result",result is VisionContract.SpecValidationResult.Valid)
                    assertEquals("$arm backend changed",probes.getJSONObject(arm).getString("activeBackend"),seg.activeBackendType?.name)
                    val buffer=inputBuffer(rgb); val destination=FloatArray(outputFloats); repeat(5){seg.runDirectRawProbabilities(buffer,destination)}
                    val before=temp(); val samples=ArrayList<Double>(30); repeat(30){samples.add(seg.runDirectRawProbabilities(buffer,destination)/1e6)}; val after=temp()
                    blocks.put(JSONObject().put("block",block).put("position",position).put("arm",arm).put("backend",seg.activeBackendDescription).put("initMs",init).put("warmups",5).put("timedRuns",30).put("thermalBefore",before).put("thermalAfter",after).put("stats",stats(samples)))
                } finally { seg.close() }
            }
            if(block<orders.lastIndex) SystemClock.sleep(15_000L)
        }
        val ratios=JSONObject()
        for(arm in listOf("pruned_20pct","pruned_40pct")) ratios.put(arm,JSONArray((0..1).map{b->blocks.firstMatch(b,arm)/blocks.firstMatch(b,"baseline")}))
        report.put("arms",JSONArray(arms)).put("timingInput","identical deterministic RGB uint8 converted to FP32 [0,1]; runDirectRawProbabilities boundary").put("blocks",blocks).put("candidateCpu4tVsCandidateBaselineP50Ratios",ratios).put("productionGpuIsSeparateBackendBaseline",true)
        File(root,"latency-results.json").writeText(report.toString(2))
        android.util.Log.i("VisionPruningBenchmark","LATENCY_RESULT_PATH=${File(root,"latency-results.json").absolutePath}")
    }

    private fun JSONArray.firstMatch(block:Int,arm:String):Double {
        for(i in 0 until length()) { val row=getJSONObject(i); if(row.getInt("block")==block && row.getString("arm")==arm) return row.getJSONObject("stats").getDouble("p50Ms") }
        error("Missing latency block $block arm $arm")
    }

    @Test fun benchmarkAllGpuArmsAndExportGroundTruthMasks() {
        require(root.isDirectory)
        val fixture=loadFixture()
        val expected=JSONObject(File(root,"model-manifest.json").readText()).getJSONObject("models")
        val modelFiles=linkedMapOf<String,File>()
        arms.forEach { arm ->
            val f=if(arm=="production") File(context.cacheDir,"pruning-production.tflite") else File(root,"models/model_${arm}.tflite")
            if(arm=="production" && !f.exists()) context.assets.open(VisionContract.DEFAULT_ASSET_MODEL_PATH).use { src -> f.outputStream().use(src::copyTo) }
            require(f.isFile) { "Missing model copy: ${f.absolutePath}" }
            modelFiles[arm]=f
        }
        val info=JSONObject().put("device",Build.MODEL).put("deviceProduct",Build.PRODUCT).put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT)
        val hashes=JSONObject()
        modelFiles.forEach { (name,file)->
            val expectedModel=expected.getJSONObject(name)
            assertEquals("$name byte count",expectedModel.getLong("bytes"),file.length())
            assertEquals("$name host/device SHA",expectedModel.getString("sha256"),sha(file))
            hashes.put(name,JSONObject().put("sha256",sha(file)).put("bytes",file.length()))
        }
        assertEquals("Production model bytes",VisionContract.DEFAULT_ASSET_MODEL_BYTES,modelFiles.getValue("production").length())
        assertEquals("Production model SHA",VisionContract.DEFAULT_ASSET_MODEL_SHA256,sha(modelFiles.getValue("production")))
        val report=JSONObject().put("device",info).put("models",hashes).put("contract",JSONObject().put("inputShape",JSONArray(listOf(1,512,288,3))).put("outputShape",JSONArray(listOf(1,512,288,7))).put("inputType","FLOAT32 [0,1]").put("outputType","FLOAT32").put("gpu","LiteRT GPU FP32 (CompiledModel)"))
        val gpuProbes=JSONObject(); var everyArmGpu=true
        for(arm in arms) {
            val seg=LiteRtVisionSegmenter(context=context, inferencePolicy=ProductionGpuOnlyPolicy)
            try {
                val result=seg.loadModelFile(modelFiles.getValue(arm))
                val cause=seg.gpuFallbackCause
                val probe=JSONObject().put("contractValidation",result.toString()).put("activeBackend",seg.activeBackendType?.name).put("activeBackendDescription",seg.activeBackendDescription).put("gpuFallbackClass",cause?.javaClass?.name).put("gpuFallbackMessage",cause?.message)
                gpuProbes.put(arm,probe)
                if(seg.activeBackendType!=VisionBackendType.GPU || seg.activeBackendDescription!="LiteRT GPU FP32 (CompiledModel)") everyArmGpu=false
            } finally { seg.close() }
        }
        report.put("strictGpuCompileProbes",gpuProbes)
        File(root,"device-results.json").writeText(report.toString(2))
        val scores=JSONObject()
        // Close the exported-model accuracy gap even when a candidate cannot compile on GPU.
        // The selected backend is recorded per arm; CPU fallback predictions are never presented as GPU evidence.
        for(arm in arms) {
            val seg=LiteRtVisionSegmenter(context=context, inferencePolicy=ProductionGpuOnlyPolicy)
            try {
                val initStart=SystemClock.elapsedRealtimeNanos(); val result=seg.loadModelFile(modelFiles.getValue(arm)); val initMs=(SystemClock.elapsedRealtimeNanos()-initStart)/1e6
                assertTrue("$arm load failed: $result", result is VisionContract.SpecValidationResult.Valid)
                val probe=gpuProbes.getJSONObject(arm)
                assertEquals("$arm backend must match strict GPU compile probe",probe.getString("activeBackend"),seg.activeBackendType?.name)
                val dir=File(root,"masks/$arm").apply{mkdirs()}; val destination=FloatArray(outputFloats)
                fixture.forEachIndexed { i,rec ->
                    val rgb=rec.copyOfRange(0,pixels*3); seg.runDirectRawProbabilities(inputBuffer(rgb),destination)
                    val mask=ByteArray(pixels)
                    for(p in 0 until pixels){ var best=0; var v=destination[p*7]; for(c in 1..6){val x=destination[p*7+c];if(x>v){v=x;best=c}}; mask[p]=best.toByte() }
                    File(dir,"record_%02d.mask".format(i)).writeBytes(mask)
                }
                scores.put(arm,JSONObject().put("initMs",initMs).put("realLabelRecords",fixture.size).put("accuracyBackend",seg.activeBackendDescription).put("strictGpuFp32",seg.activeBackendType==VisionBackendType.GPU).put("gpuFallbackClass",seg.gpuFallbackCause?.javaClass?.name).put("gpuFallbackMessage",seg.gpuFallbackCause?.message))
            } finally { seg.close() }
        }
        report.put("realLabelPass",scores)
        // Candidate GPU compilation can fail while production succeeds. In that case, measure
        // explicitly labeled CPU4T fallback latency for candidates and production GPU separately.
        val rng=Random(20261106L); val rgb=ByteArray(VisionContract.INPUT_BYTES/4); rng.nextBytes(rgb)
        val timing=JSONArray()
        val blockOrders=if(everyArmGpu) listOf(
            listOf("baseline","pruned_20pct","pruned_40pct","production"),
            listOf("pruned_20pct","production","baseline","pruned_40pct"),
            listOf("pruned_40pct","baseline","production","pruned_20pct"),
            listOf("production","pruned_40pct","pruned_20pct","baseline"),
        ) else listOf(
            listOf("baseline","pruned_20pct","pruned_40pct","production"),
            listOf("production","pruned_40pct","pruned_20pct","baseline"),
        )
        for(block in blockOrders.indices) {
            val order=blockOrders[block]
            order.forEachIndexed { position,arm ->
                val seg=LiteRtVisionSegmenter(context=context, inferencePolicy=ProductionGpuOnlyPolicy)
                try {
                    val initStart=SystemClock.elapsedRealtimeNanos(); val result=seg.loadModelFile(modelFiles.getValue(arm)); val initMs=(SystemClock.elapsedRealtimeNanos()-initStart)/1e6
                    assertTrue("$arm load failed",result is VisionContract.SpecValidationResult.Valid)
                    assertEquals("$arm timing backend must match compile probe",gpuProbes.getJSONObject(arm).getString("activeBackend"),seg.activeBackendType?.name)
                    if(everyArmGpu) assertEquals("LiteRT GPU FP32 (CompiledModel)",seg.activeBackendDescription)
                    val buf=inputBuffer(rgb); val out=FloatArray(outputFloats); repeat(5){seg.runDirectRawProbabilities(buf,out)}
                    val before=temp(); val samples=ArrayList<Double>(30); repeat(30){samples.add(seg.runDirectRawProbabilities(buf,out)/1e6)}; val after=temp()
                    timing.put(JSONObject().put("block",block).put("position",position).put("arm",arm).put("backend",seg.activeBackendDescription).put("initMs",initMs).put("warmups",5).put("thermalBefore",before).put("thermalAfter",after).put("stats",stats(samples)))
                } finally { seg.close() }
            }
            if(block<blockOrders.lastIndex) SystemClock.sleep(15_000L) // bounded cool-off between blocks
        }
        report.put("realLabelPass",scores).put("timingBlocks",timing).put("fixtureRecords",fixture.size).put("timingInput", "same deterministic random RGB [0,1] for all arms; runDirectRawProbabilities boundary")
        if(!everyArmGpu) report.put("latencyBenchmarkStatus","candidate GPU unavailable; candidate timing is CPU4T production-policy fallback; production timing is GPU FP32 CompiledModel; backend comparisons are not causal or apples-to-apples")
        val output=File(root,"device-results.json"); output.writeText(report.toString(2));
        android.util.Log.i("VisionPruningBenchmark","RESULT_PATH=${output.absolutePath}")
        assertTrue(output.length()>1000)
        if(!everyArmGpu) assertTrue("Candidate GPU compile failed; CPU4T diagnostic candidate timings and accuracy metrics plus separate production GPU timing are in device-results.json. No pruning GPU speed claim is possible.",false)
    }
}
