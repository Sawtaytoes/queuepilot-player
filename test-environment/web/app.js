import RFB from '/novnc/core/rfb.js';

const element = (id) => document.getElementById(id);
const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
const phases = [
  'Preparing the Android TV device',
  'Starting the display',
  'Starting Android',
  'Android is booting',
  'Installing QueuePilot',
];

let control = null;
let status = null;
let viewer = null;
let isViewerConnecting = false;
let hasRequestedStart = false;
let noticeTimer = null;
let clockOffset = 0;
let lastWarning = null;

function send(message) {
  if (control && control.readyState === WebSocket.OPEN) control.send(JSON.stringify(message));
}

function showNotice(text, level = 'info') {
  const notice = element('notice');
  notice.textContent = text;
  notice.dataset.level = level;
  notice.hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { notice.hidden = true; }, 5000);
}

function connectControl() {
  control = new WebSocket(`${scheme}//${location.host}/control`);
  control.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    if (message.type === 'status') {
      clockOffset = Date.now() / 1000 - message.serverTime;
      render(message);
    }
    else if (message.type === 'notice') showNotice(message.text, message.level);
  });
  control.addEventListener('close', () => {
    element('state').textContent = 'Reconnecting';
    element('state').dataset.state = 'error';
    setTimeout(async () => {
      // A closed socket after a long absence is usually an expired sign-in.
      const response = await fetch('/api/status', { headers: { Accept: 'application/json' } }).catch(() => null);
      if (response && response.status === 401) location.reload();
      else connectControl();
    }, 2000);
  });
}

function connectViewer() {
  if (viewer || isViewerConnecting) return;
  isViewerConnecting = true;
  const rfb = new RFB(element('display'), `${scheme}//${location.host}/vnc`, { wsProtocols: ['binary'] });
  rfb.viewOnly = true;
  rfb.scaleViewport = true;
  rfb.qualityLevel = 7;
  rfb.compressionLevel = 1;
  rfb.background = '#000000';
  rfb.addEventListener('connect', () => {
    viewer = rfb;
    isViewerConnecting = false;
    render(status);
  });
  rfb.addEventListener('disconnect', () => {
    viewer = null;
    isViewerConnecting = false;
    render(status);
    if (status && ['starting', 'running'].includes(status.state) && status.isDisplayReady) {
      setTimeout(connectViewer, 1500);
    }
  });
}

function formatDuration(seconds) {
  const whole = Math.max(0, Math.round(seconds));
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
}

function render(next) {
  if (!next) return;
  status = next;
  const serverNow = Date.now() / 1000 - clockOffset;
  const stateLabel = {
    stopped: 'Stopped', starting: 'Starting', running: 'Running', stopping: 'Stopping', error: 'Error',
  }[status.state] || status.state;
  element('state').textContent = status.test ? 'Automated test' : stateLabel;
  element('state').dataset.state = status.test ? 'test' : status.state;

  const app = status.app || {};
  element('app').textContent = app.versionName
    ? `QueuePilot ${app.versionName}${app.source === 'pinned' ? ' (test build)' : ''}`
    : '';
  element('app').title = app.warning || '';
  if (app.warning && app.warning !== lastWarning) showNotice(app.warning, 'warning');
  lastWarning = app.warning;

  const banner = element('banner');
  if (status.test) {
    const started = new Date(status.test.startedAt * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    const step = { starting: 'starting Android', saving: 'saving your session', running: 'running',
      restoring: 'restoring your session' }[status.test.status] || status.test.status;
    banner.textContent = `In use by an automated test since ${started}: ${status.test.label}. `
      + `The test is ${step}. Input is blocked. Your session returns when the test finishes.`;
    banner.hidden = false;
  } else {
    banner.hidden = true;
  }
  document.body.classList.toggle('is-locked', Boolean(status.test));

  const isLive = ['starting', 'running'].includes(status.state) && status.isDisplayReady;
  if (isLive) connectViewer();
  else if (viewer) viewer.disconnect();

  const overlay = element('overlay');
  const progress = element('progress');
  const startButton = element('start');
  startButton.hidden = true;
  progress.hidden = true;
  if (status.state === 'running' && viewer) {
    overlay.hidden = true;
  } else {
    overlay.hidden = false;
    if (status.state === 'starting') {
      element('overlayTitle').textContent = 'Starting Android TV';
      const elapsed = status.startedAt ? serverNow - status.startedAt : 0;
      element('overlayDetail').textContent = `${status.phase} (${formatDuration(elapsed)})`;
      const index = Math.max(0, phases.indexOf(status.phase));
      progress.hidden = false;
      progress.firstElementChild.style.width = `${Math.round(((index + 1) / (phases.length + 1)) * 100)}%`;
      overlay.classList.toggle('is-translucent', Boolean(viewer));
    } else if (status.state === 'running') {
      element('overlayTitle').textContent = 'Connecting to the display';
      element('overlayDetail').textContent = '';
    } else if (status.state === 'stopping') {
      element('overlayTitle').textContent = 'Stopping';
      element('overlayDetail').textContent = '';
    } else {
      overlay.classList.remove('is-translucent');
      const stop = status.lastStop;
      const reason = stop && stop.reason === 'idle'
        ? `Stopped after ${status.idleMinutes} minutes without input.`
        : (stop ? `Stopped: ${stop.reason}.` : 'Android TV is not running.');
      element('overlayTitle').textContent = status.state === 'error' ? 'Android TV did not start' : 'Android TV is stopped';
      element('overlayDetail').textContent = status.state === 'error' ? status.error : reason;
      startButton.hidden = false;
    }
  }

  element('stop').hidden = !(status.state === 'running' && !status.test);
  const idle = element('idle');
  const keepRunning = element('keepRunning');
  if (status.idleStopAt) {
    const remaining = status.idleStopAt - serverNow;
    idle.textContent = remaining <= status.idleWarningSeconds
      ? `No input for a while. Android stops in ${formatDuration(remaining)}.`
      : `Stops after ${status.idleMinutes} minutes without input.`;
    idle.classList.toggle('is-warning', remaining <= status.idleWarningSeconds);
    keepRunning.hidden = remaining > status.idleWarningSeconds;
  } else {
    idle.textContent = status.test ? 'The idle stop waits for the automated test.' : '';
    idle.classList.remove('is-warning');
    keepRunning.hidden = true;
  }

  if (!hasRequestedStart && ['stopped', 'error'].includes(status.state)) {
    // Opening the page starts the environment. An idle stop while the page stays
    // open does not restart it; that needs the Start button.
    hasRequestedStart = true;
    if (status.state === 'stopped') send({ type: 'start' });
  }
  hasRequestedStart = true;
}

function press(key) {
  if (!status || status.test) {
    showNotice('An automated test is using the emulator. Input is blocked until it finishes.', 'warning');
    return;
  }
  send({ type: 'key', key });
}

const keyboard = {
  ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'ok',
  Escape: 'back', Backspace: 'back', Home: 'home', h: 'home', H: 'home', ' ': 'playPause',
  MediaPlayPause: 'playPause', k: 'playPause', ',': 'rewind', '.': 'fastForward', m: 'menu',
};

document.addEventListener('keydown', (event) => {
  if (event.target instanceof HTMLInputElement || event.ctrlKey || event.metaKey || event.altKey) return;
  const key = keyboard[event.key];
  if (!key) return;
  event.preventDefault();
  press(key);
});

for (const button of document.querySelectorAll('[data-key]')) {
  button.addEventListener('pointerdown', (event) => {
    event.preventDefault();
    press(button.dataset.key);
  });
  button.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      event.stopPropagation();
      press(button.dataset.key);
    }
  });
}

element('textForm').addEventListener('submit', (event) => {
  event.preventDefault();
  const input = element('text');
  if (!input.value) return;
  if (status && status.test) {
    showNotice('An automated test is using the emulator. Input is blocked until it finishes.', 'warning');
    return;
  }
  send({ type: 'text', text: input.value });
  input.value = '';
});

element('start').addEventListener('click', () => send({ type: 'start' }));
element('stop').addEventListener('click', () => send({ type: 'stop' }));
element('keepRunning').addEventListener('click', () => send({ type: 'keepRunning' }));
setInterval(() => render(status), 1000);
connectControl();
