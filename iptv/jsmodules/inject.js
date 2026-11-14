export var process = { env: {}, browser: true, version: '', versions: {}, nextTick: function (f, ...a) { Promise.resolve().then(() => f(...a)); } };
export { Buffer } from 'buffer';
