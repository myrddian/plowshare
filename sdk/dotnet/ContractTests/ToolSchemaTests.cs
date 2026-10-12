using System.Text.Json;
using Plowshare.Sdk;
internal static class ToolSchemaChecks
{
    internal static void Run()
    {
        using var fixture = JsonDocument.Parse(File.ReadAllText(Path.Combine(Path.GetDirectoryName(Environment.GetEnvironmentVariable("PLOWSHARE_SDK_DTO_FIXTURES"))!, "tool-schemas.json")));
        foreach (var row in fixture.RootElement.GetProperty("cases").EnumerateArray())
        {
            var name = row.GetProperty("name").GetString();
            SchemaDefinition? schema = null;
            var accepted = true;
            try
            {
                schema = ToolSchemas.Decode(row.GetProperty("schemaSource").GetString()!);
            }
            catch (ArgumentException)
            {
                accepted = false;
            }
            catch (JsonException)
            {
                accepted = false;
            }
            if (accepted != row.GetProperty("validSchema").GetBoolean()) throw new Exception("Schema acceptance differs: " + name);
            if (schema is null) continue;
            if (ToolSchemas.Fingerprint(schema) != row.GetProperty("fingerprint").GetString()) throw new Exception("Schema fingerprint differs: " + name);
            accepted = true;
            try
            {
                using var args = JsonDocument.Parse(row.GetProperty("argumentsSource").GetString()!);
                ToolSchemas.ValidateArguments(schema, ToolSchemas.DecodeValue(args.RootElement));
            }
            catch (ArgumentException)
            {
                accepted = false;
            }
            catch (JsonException)
            {
                accepted = false;
            }
            if (accepted != row.GetProperty("validArguments").GetBoolean()) throw new Exception("Argument acceptance differs: " + name);
        }
        Console.WriteLine(".NET shared tool schema checks passed");
    }
}
