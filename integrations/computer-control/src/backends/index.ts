export { createBrowserBackend, decodeBrowserBackendConfig } from './browser.ts';
export type {
  BrowserBackendConfig,
  BrowserCaptureDriver,
  BrowserDriverFactory,
  BrowserTargetConfig,
} from './browser.ts';
export {
  createMacOSBackend,
  decodeMacOSBackendConfig,
  SpawnNativeCaptureProcess,
} from './macos.ts';
export type {
  MacOSBackendConfig,
  MacOSTargetConfig,
  NativeCaptureProcess,
  NativeRequest,
} from './macos.ts';
export type { BackendHandle } from './types.ts';
