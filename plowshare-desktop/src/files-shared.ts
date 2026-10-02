export interface FilePresenceState {
  status: 'off' | 'opening' | 'ready' | 'lost';
  project?: string;
  root?: string;
  machine?: string;
  detail?: string;
}
