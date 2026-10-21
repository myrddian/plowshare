using System.Text.Json;
using Plowshare.Sdk;
// Synthetic fixtures are the only credentials printed by this test driver.
var input = JsonSerializer.Deserialize<Input>(await Console.In.ReadToEndAsync())!;
string account = string.IsNullOrEmpty(input.Account) ? "alice" : input.Account;
try
{
    if(input.Action=="key") {Console.WriteLine(JsonSerializer.Serialize(new {key=Credentials.ConnectionKey(input.Origin,account)}));return;}
    var store = input.Action == "resolve" ? Credentials.Resolve(input.Origin.Length == 0 ? null : input.Origin, input.Account.Length == 0 ? null : input.Account, input.Connection.Length == 0 ? null : input.Connection, (input.Root.Length==0?null:input.Root)) : new Credentials(input.Origin, account, (input.Root.Length==0?null:input.Root), TimeSpan.FromSeconds(2));
    switch (input.Action)
    {
        case "login": await store.LoginAsync(account, "fixture-password"); break;
        case "renew": await store.RenewAsync(rejectedAccess: input.Rejected.Length == 0 ? null : input.Rejected); break;
        case "authenticated": await store.AuthenticatedAsync(); break;
        case "logout": await store.LogoutAsync(); Console.WriteLine("{}"); return;
    }
    var session = await store.SessionAsync();
    Console.WriteLine(JsonSerializer.Serialize(new { access = session.Tokens.Access, handle = session.Handle, key = Credentials.ConnectionKey(session.Origin, session.Handle) }));
}
catch (Exception error) when (error is IOException or ArgumentException or JsonException) { Console.WriteLine(JsonSerializer.Serialize(new { failed = true, loginRequired = error is LoginRequiredException })); }
internal sealed record Input(string Root, string Origin, string Action, string Rejected, string Account, string Connection);
