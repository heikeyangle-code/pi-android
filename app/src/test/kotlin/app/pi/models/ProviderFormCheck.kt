package app.pi.models

// 裸 JVM harness：自定义供应商表单 ↔ models.json 的 provider 对象。
// 钉住三件事：读出再写回**不丢**表单没有界面的字段、空字段不写、数字写错时点名是哪一个。

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("PASS $name") else { failures++; println("FAIL $name $detail") }
}
private fun obj(s: String) = Json {}.parseToJsonElement(s) as JsonObject
private fun ProviderForm.ok(): JsonObject = (toJson() as ProviderForm.Result.Ok).json

fun main() {
    val src = obj("""{"baseUrl":"https://x/v1","api":"openai-completions","apiKey":"!op read k","headers":{"X-A":"1","X-B":"two"},
      "compat":{"supportsStore":false},
      "models":[{"id":"m1","name":"M one","reasoning":true,"input":["text","image"],"contextWindow":128000,"maxTokens":8192,"cost":{"input":1,"output":2,"cacheRead":0.1,"cacheWrite":0,"tiers":[{"input":2,"output":4,"cacheRead":0,"cacheWrite":0,"inputTokensAbove":200000}]}},
                {"id":"m2","input":["text","audio"]}]}""")
    val form = ProviderForm.from("mine", src)
    check("读出：基本字段", form.baseUrl == "https://x/v1" && form.api == "openai-completions" && form.apiKey == "!op read k")
    check("读出：请求头成行", form.headersText == "X-A: 1\nX-B: two", form.headersText)
    check("读出：模型", form.models.size == 2 && form.models[0].reasoning && form.models[0].acceptsImages && form.models[0].contextWindow == "128000")
    check("读出：认不得的 provider 字段进 extra", form.extra.containsKey("compat"))
    check("读出：认不得的 model 字段进 extra", form.models[0].extra.isEmpty(), form.models[0].extra.toString())
    check("读出：价格拆成四个字段，分档价留在 costExtra",
        form.models[0].costInput == "1" && form.models[0].costOutput == "2" && form.models[0].costCacheRead == "0.1" &&
            form.models[0].costCacheWrite == "0" && form.models[0].costExtra.containsKey("tiers"))

    val back = form.ok()
    check("原样往返不丢任何字段", back == src, back.toString())
    check("往返：除了 audio 之外的奇怪 input 值不被吞", (back["models"] as JsonArray)[1].toString().contains("audio"))

    val edited = form.copy(baseUrl = "  ", apiKey = "", headersText = "")
    val j = edited.ok()
    check("空字段不写", !j.containsKey("baseUrl") && !j.containsKey("apiKey") && !j.containsKey("headers"))
    check("清空请求头会删掉 headers", !j.containsKey("headers"))

    val noModels = ProviderForm(id = "p", baseUrl = "u").ok()
    check("没有模型就没有 models 键", !noModels.containsKey("models") && noModels["baseUrl"] == JsonPrimitive("u"))

    val m = ProviderForm.ModelForm(id = "a", reasoning = false, acceptsImages = false)
    val plain = ProviderForm(models = listOf(m)).ok()
    val mo = (plain["models"] as JsonArray)[0] as JsonObject
    check("普通模型只有 id", mo.keys == setOf("id"), mo.toString())

    val badNum = ProviderForm(models = listOf(m.copy(contextWindow = "12k"))).toJson()
    check("数字写错点名", badNum is ProviderForm.Result.Invalid && badNum.message.contains("「a」") && badNum.message.contains("上下文窗口"), badNum.toString())
    check("零和负数不行", ProviderForm(models = listOf(m.copy(maxTokens = "0"))).toJson() is ProviderForm.Result.Invalid)
    check("没填 ID 被拒", ProviderForm(models = listOf(ProviderForm.ModelForm())).toJson() is ProviderForm.Result.Invalid)
    check("ID 重复被拒", ProviderForm(models = listOf(m, m)).toJson().let { it is ProviderForm.Result.Invalid && it.message.contains("重复") })
    check("请求头缺冒号被拒", ProviderForm(headersText = "oops").toJson() is ProviderForm.Result.Invalid)
    check("请求头值里可以有冒号", ProviderForm.parseHeaders("Authorization: Bearer a:b") == mapOf("Authorization" to "Bearer a:b"))


    // ---- name / authHeader（SDK 的 ProviderConfigSchema）----
    val named = ProviderForm.from("p", obj("""{"name":"My Proxy","baseUrl":"u","apiKey":"k","authHeader":true}"""))
    check("读出：name / authHeader", named.name == "My Proxy" && named.authHeader)
    check("往返：name / authHeader 不丢", named.ok() == obj("""{"name":"My Proxy","baseUrl":"u","apiKey":"k","authHeader":true}"""), named.ok().toString())
    val off = named.copy(authHeader = false, name = " ").ok()
    check("authHeader=false 与空名字都不写", !off.containsKey("authHeader") && !off.containsKey("name"))

    // ---- 价格：SDK 的 ModelCostSchema 四项全必填 ----
    val cm = ProviderForm.ModelForm(id = "c")
    fun costOf(f: ProviderForm.ModelForm): JsonObject? =
        (((ProviderForm(models = listOf(f)).ok()["models"] as JsonArray)[0] as JsonObject)["cost"] as? JsonObject)
    check("没填价格就没有 cost 键", costOf(cm) == null)
    check("填了输入输出：缓存读写补 0，整数写成整数",
        costOf(cm.copy(costInput = "3", costOutput = "15.5")) == obj("""{"input":3,"output":15.5,"cacheRead":0,"cacheWrite":0}"""),
        costOf(cm.copy(costInput = "3", costOutput = "15.5")).toString())
    check("只填输入 → 被拒并点名", ProviderForm(models = listOf(cm.copy(costInput = "3"))).toJson().let { it is ProviderForm.Result.Invalid && it.message.contains("「c」") })
    check("价格不是数字 → 被拒并点名哪一项",
        ProviderForm(models = listOf(cm.copy(costInput = "1", costOutput = "abc"))).toJson().let { it is ProviderForm.Result.Invalid && it.message.contains("输出价格") })
    check("负价格 → 被拒", ProviderForm(models = listOf(cm.copy(costInput = "-1", costOutput = "1"))).toJson() is ProviderForm.Result.Invalid)
    check("NaN / Infinity → 被拒", ProviderForm(models = listOf(cm.copy(costInput = "NaN", costOutput = "1"))).toJson() is ProviderForm.Result.Invalid &&
        ProviderForm(models = listOf(cm.copy(costInput = "Infinity", costOutput = "1"))).toJson() is ProviderForm.Result.Invalid)
    val cleared = ProviderForm.from("mine", src).let { f -> f.copy(models = f.models.map { it.copy(costInput = "", costOutput = "", costCacheRead = "", costCacheWrite = "") }) }.ok()
    check("清空四个价格会删掉 cost（分档价随之去掉，因为没有基础价它不合法）", !(((cleared["models"] as JsonArray)[0] as JsonObject).containsKey("cost")))
    val weird = ProviderForm.from("w", obj("""{"baseUrl":"u","models":[{"id":"x","cost":"free"}]}"""))
    check("cost 不是对象：原样留在 extra，保存不动它", weird.models[0].extra.containsKey("cost") && weird.ok()["models"].toString().contains("free"))

    if (failures > 0) { println("\nharness: FAILED ($failures)"); System.exit(1) }
    println("\nharness: OK (all checks passed)")
}
