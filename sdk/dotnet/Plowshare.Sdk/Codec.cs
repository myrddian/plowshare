using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;

namespace Plowshare.Sdk;

/// <summary>Protocol null, including an operation with no result body.</summary>
[JsonConverter(typeof(UnitConverter))]
public readonly record struct Unit;
internal sealed class UnitConverter : JsonConverter<Unit>
{
    public override Unit Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options)
        => reader.TokenType == JsonTokenType.Null ? default : throw new JsonException("Expected protocol null");
    public override void Write(Utf8JsonWriter writer, Unit value, JsonSerializerOptions options) => writer.WriteNullValue();
}

/// <summary>Field presence is independent of its value. Default means omitted.</summary>
[JsonConverter(typeof(OptionalConverterFactory))]
public readonly record struct Optional<T>
{
    public bool IsSet { get; }
    private readonly T value;
    public T Value => IsSet ? value : throw new InvalidOperationException("Field was omitted");
    public Optional(T value) { this.value = value; IsSet = true; }
    public static implicit operator Optional<T>(T value) => new(value);
}
internal sealed class OptionalConverterFactory : JsonConverterFactory
{
    public override bool CanConvert(Type type) => type.IsGenericType && type.GetGenericTypeDefinition() == typeof(Optional<>);
    public override JsonConverter CreateConverter(Type type, JsonSerializerOptions options)
        => (JsonConverter)Activator.CreateInstance(typeof(OptionalConverter<>).MakeGenericType(type.GetGenericArguments()))!;
    private sealed class OptionalConverter<T> : JsonConverter<Optional<T>>
    {
        public override bool HandleNull => true;
        public override Optional<T> Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options)
            => new(JsonSerializer.Deserialize<T>(ref reader, options)!);
        public override void Write(Utf8JsonWriter writer, Optional<T> value, JsonSerializerOptions options)
        {
            if (!value.IsSet) throw new JsonException("Omitted field cannot be encoded as a value");
            JsonSerializer.Serialize(writer, value.Value, options);
        }
    }
}

/// <summary>Owns raw JSON conversion. Generated operations expose only validated DTOs.</summary>
internal static class Codec
{
    private static readonly JsonDocument catalog = LoadCatalog();
    private static JsonDocument LoadCatalog()
    {
        using var stream = typeof(Codec).Assembly.GetManifestResourceStream("Plowshare.Sdk.Schemas.json")
            ?? throw new InvalidOperationException("Missing generated SDK contract");
        return JsonDocument.Parse(stream);
    }
    private static JsonElement Definitions => catalog.RootElement.GetProperty("$defs");
    private static JsonException Invalid() => new("Value does not match the owned protocol contract");
    public static bool Matches(string key, JsonElement value)
    {
        try { Decode(Definitions.GetProperty(key), value, false, 0); return true; }
        catch (JsonException) { return false; }
    }
    public static JsonElement Input<T>(string operation, T request)
    {
        var raw = JsonSerializer.SerializeToElement(request);
        var projected = Decode(catalog.RootElement.GetProperty("inputs").GetProperty(operation), raw, true, 0);
        return JsonSerializer.SerializeToElement(projected);
    }
    public static T Result<T>(string operation, JsonElement value)
    {
        var projected = Decode(catalog.RootElement.GetProperty("results").GetProperty(operation), value, false, 0);
        return JsonSerializer.SerializeToElement(projected).Deserialize<T>()!;
    }
    // Receipt ownership supplements structural decoding before the result escapes this boundary.
    public static void CorrelateDeployment(string operation, JsonElement request, JsonElement result)
    {
        if (operation is not ("application.deploy" or "application.activate" or "application.deployment.status" or "application.deployment.receipt")) return;
        static string? TextAt(JsonElement row, string name) => row.ValueKind == JsonValueKind.Object && row.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
        if (TextAt(request,"project") != TextAt(result,"project") || operation != "application.deployment.status" && !string.Equals(TextAt(request,"requestId"),TextAt(result,"requestId"),StringComparison.OrdinalIgnoreCase)) throw Invalid();
        if (operation == "application.activate" && (!result.TryGetProperty("release",out var release) || !string.Equals(TextAt(request,"revision"),TextAt(release,"revision"),StringComparison.OrdinalIgnoreCase))) throw Invalid();
    }
    public static ServerPush Push(JsonElement frame)
    {
        JsonElement raw = frame;
        if (frame.TryGetProperty("protocol_version", out var version))
        {
            if (version.ValueKind != JsonValueKind.String || version.GetString() != Protocol.Version || !frame.TryGetProperty("id", out var id) || id.ValueKind != JsonValueKind.Null) throw Invalid();
            if (!frame.TryGetProperty("payload", out var payload) || payload.ValueKind != JsonValueKind.Object
                || !frame.TryGetProperty("type", out var kind) || kind.ValueKind != JsonValueKind.String) throw Invalid();
            var row = JsonNode.Parse(payload.GetRawText()) as JsonObject ?? throw Invalid();
            row["type"] = kind.GetString();
            raw = JsonSerializer.SerializeToElement(row);
        }
        var projected = Decode(catalog.RootElement.GetProperty("pushes"), raw, false, 0);
        return new ServerPush(JsonSerializer.SerializeToElement(projected).Deserialize<Notification>()!);
    }
    // Validate and project before deserialization. DTO constructors and serializer
    // annotations alone cannot establish that required fields and variants are valid.
    private static JsonNode? Decode(JsonElement schema, JsonElement value, bool input, int depth)
    {
        Validation.Check(schema, value);
        if (depth > 64 || schema.TryGetProperty("forbidden", out _)) throw Invalid();
        if (schema.TryGetProperty("$ref", out var reference))
            return Decode(Definitions.GetProperty(reference.GetString()!.Replace("#/$defs/", "")), value, input, depth + 1);
        if (schema.TryGetProperty("anyOf", out var variants))
        {
            foreach (var variant in variants.EnumerateArray())
                try { return Decode(variant, value, input, depth + 1); } catch (JsonException) { /* Require a complete variant. */ }
            throw Invalid();
        }
        if (schema.TryGetProperty("const", out var literal))
        {
            if (!JsonNode.DeepEquals(JsonNode.Parse(literal.GetRawText()), value.ValueKind == JsonValueKind.Undefined ? null : JsonNode.Parse(value.GetRawText()))) throw Invalid();
            return JsonNode.Parse(literal.GetRawText());
        }
        var type = schema.TryGetProperty("type", out var kind) ? kind.GetString() : null;
        if (schema.TryGetProperty("optional", out _) || type == "null")
        {
            if (value.ValueKind is not (JsonValueKind.Null or JsonValueKind.Undefined)) throw Invalid();
            return null;
        }
        switch (type)
        {
            case "string":
                if (value.ValueKind != JsonValueKind.String || value.GetString()!.Length > 8 * 1024 * 1024) throw Invalid();
                return JsonValue.Create(value.GetString());
            case "number":
                if (value.ValueKind != JsonValueKind.Number || !value.TryGetDouble(out var number) || !double.IsFinite(number)) throw Invalid();
                return JsonNode.Parse(value.GetRawText());
            case "boolean":
                if (value.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw Invalid();
                return JsonValue.Create(value.GetBoolean());
            case "array":
                var maximum = schema.TryGetProperty("maxItems", out var max) ? max.GetInt32() : 10000;
                var minimum = schema.TryGetProperty("minItems", out var min) ? min.GetInt32() : 0;
                if (value.ValueKind != JsonValueKind.Array || value.GetArrayLength() > maximum || value.GetArrayLength() < minimum) throw Invalid();
                var array = new JsonArray(); var index = 0;
                var tuple = schema.TryGetProperty("prefixItems", out var prefix);
                foreach (var entry in value.EnumerateArray())
                {
                    var item = tuple ? prefix[index] : schema.GetProperty("items");
                    array.Add(Decode(item, entry, input, depth + 1)); index++;
                }
                return array;
            case "object":
                if (value.ValueKind != JsonValueKind.Object || value.EnumerateObject().Count() > 10000) throw Invalid();
                if (schema.TryGetProperty("required", out var required))
                    foreach (var key in required.EnumerateArray()) if (!value.TryGetProperty(key.GetString()!, out _)) throw Invalid();
                var result = new JsonObject();
                schema.TryGetProperty("properties", out var properties);
                schema.TryGetProperty("additionalProperties", out var extra);
                foreach (var field in value.EnumerateObject())
                {
                    if (field.Name is "__proto__" or "constructor" or "prototype") throw Invalid();
                    var known = properties.ValueKind == JsonValueKind.Object && properties.TryGetProperty(field.Name, out _);
                    JsonElement shape;
                    if (known) shape = properties.GetProperty(field.Name);
                    else if (extra.ValueKind == JsonValueKind.Object) shape = extra;
                    else { if (input) throw Invalid(); continue; }
                    result[field.Name] = Decode(shape, field.Value, input, depth + 1);
                }
                return result;
        }
        throw Invalid();
    }
}
