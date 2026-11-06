export const initEngine: (modelPath: string, nCtx: number, nThreads: number) => number;
export const chatStream: (prompt: string, cb: (token: string) => void) => number;
export const synthesizeSpeech: (text: string, voice: string) => ArrayBuffer;
export const notesSearch: (query: string) => string;
export const miniAppsList: () => string;
export const runSandboxTool: (toolName: string, argsJson: string) => string;
export const destroyEngine: () => number;
