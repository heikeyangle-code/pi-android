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
      "models":[{"id":"m1","name":"M one","reasoning":true,"input":["text","image"],"contextWindow":128000,"maxTokens":8192,"cost":{"input":1,"output":2}},
                {"id":"m2","input":["text","audio"]}]}""")
    val form = ProviderForm.from("mine", src)
    check("读出：基本字段", form.baseUrl == "https://x/v1" && form.api == "openai-completions" && form.apiKey == "!op read k")
    check("读出：请求头成行", form.headersText == "X-A: 1\nX-B: two", form.headersText)
    check("读出：模型", form.models.size == 2 && form.models[0].reasoning && form.models[0].acceptsImages && form.models[0].contextWindow == "128000")
    check("读出：认不得的 provider 字段进 extra", form.extra.containsKey("compat"))
    check("读出：认不得的 model 字段进 extra", form.models[0].extra.containsKey("cost"))

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

    if (failures > 0) { println("\nharness: FAILED ($failures)"); System.exit(1) }
    println("\nharness: OK (all checks passed)")
}
