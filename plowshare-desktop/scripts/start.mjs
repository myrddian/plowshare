import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { desktopRoot, nativeApp } from './native-app.mjs';

process.chdir(desktopRoot);
await import('./build.mjs');
const env = { ...process.env };
delete env.ELECTRON_RUN_AS_NODE;
let executable, args;
if (process.platform === 'darwin') {
  executable = await nativeApp();
  args = process.argv.slice(2);
  // Keep existing development drafts and window preferences after bundling.
  env.PLOWSHARE_DESKTOP_PROFILE ??= join(desktopRoot, 'build/profile');
} else {
  executable = (await import('electron')).default;
  args = [desktopRoot, ...process.argv.slice(2)];
}
const child = spawn(executable, args, { cwd: desktopRoot, env, stdio: 'inherit' });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.on('error', error => { console.error(error); process.exitCode = 1; });
child.on('exit', (code, signal) => {
  if (signal) {
    process.removeAllListeners(signal);
    process.kill(process.pid, signal);
  }
  else process.exitCode = code ?? 1;
});
