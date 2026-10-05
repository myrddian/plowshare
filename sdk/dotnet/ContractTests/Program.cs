using System.Text.Json;
using Plowshare.Sdk;

using var fixture = JsonDocument.Parse(File.ReadAllText(Environment.GetEnvironmentVariable("PLOWSHARE_SDK_DTO_FIXTURES")!));
foreach (var test in fixture.RootElement.EnumerateArray())
{
    bool accepted = true;
    try
    {
        var boundary = test.GetProperty("boundary").GetString(); var value = test.GetProperty("value");
        if (boundary == "input") Codec.Input(test.GetProperty("operation").GetString()!, value);
        else if (boundary == "result") Codec.Result<JsonElement>(test.GetProperty("operation").GetString()!, value);
        else Codec.Push(value);
    }
    catch (JsonException) { accepted = false; }
    if (accepted != test.GetProperty("valid").GetBoolean()) throw new Exception("Boundary case failed: " + test.GetRawText());
}
// Reflection checks the full public operation surface, independently of schema decoding.
var methods = typeof(Operations).GetMethods(System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static);
if (methods.Length != Protocol.Operations.Count) throw new Exception("Missing typed operations");
foreach (var method in methods)
    if (method.GetParameters().Any(p => p.ParameterType == typeof(object) || p.ParameterType == typeof(JsonElement))) throw new Exception("Raw public request");
var absent = Codec.Input("conversation.open", new ConversationOpenRequest());
if (absent.TryGetProperty("project", out _)) throw new Exception("Absent field encoded");
var explicitNull = Codec.Input("conversation.open", new ConversationOpenRequest { Project = TierDtoProject.FromVariant1(default) });
if (explicitNull.GetProperty("project").ValueKind != JsonValueKind.Null) throw new Exception("Explicit null lost");
Console.WriteLine(".NET DTO contract checks passed");
