// Installed launcher: keep the caller's working directory for project discovery.
export async function talk(args) {
  const help = `plowshare talk [--connection NAME | --url URL] [--account HANDLE] [--agent NAME] [--project NAME]
Open the Plowshare terminal. Sign in first with plowshare login.
Direct commands: conversation, memory, information, usage (add --help).
Manage connections: connection list|add|select|rename|remove ARGS...
`;
  const names = { '--url': 'PLOWSHARE_URL', '--server': 'PLOWSHARE_URL', '--connection': 'PLOWSHARE_CONNECTION', '--account': 'PLOWSHARE_ACCOUNT', '--agent': 'PLOWSHARE_AGENT', '--project': 'PLOWSHARE_PROJECT' };
  let invalid = false;
  while (args.length && !['conversation', 'memory', 'information', 'usage', 'connection'].includes(args[0])) {
    const flag = args.shift();
    if (flag === '--help' || flag === '-h') { process.stdout.write(help); process.exit(0); }
    if (!names[flag] || !args[0] || args[0].startsWith('--')) {
      process.stderr.write(`Unknown option or missing value: ${flag}\n${help}`); invalid = true; break;
    }
    process.env[names[flag]] = args.shift();
  }
  if (invalid) process.exitCode = 2;
  else if (args[0] === 'connection') {
    const { run } = await import('../plowshare-tui/src/view/main.ts');
    await run(args);
  } else if (args.length) {
    const { retrievalCli } = await import('../plowshare-tui/src/view/retrieval-cli.ts');
    process.exitCode = await retrievalCli(args, process.env,
      text => process.stdout.write(text + '\n'), text => process.stderr.write(text + '\n'));
  } else {
    const { run } = await import('../plowshare-tui/src/view/main.ts');
    await run();
  }
}
