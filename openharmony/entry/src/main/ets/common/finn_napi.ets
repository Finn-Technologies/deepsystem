import nativeModule from 'libfinn_napi.so';

export interface ChatStreamCallback {
  (token: string): void;
}

export const finn = {
  initEngine(modelPath: string, nCtx: number, nThreads: number): number {
    return nativeModule.initEngine(modelPath, nCtx, nThreads);
  },
  chatStream(prompt: string, cb: ChatStreamCallback): number {
    return nativeModule.chatStream(prompt, (t: string) => cb(t));
  },
  synthesizeSpeech(text: string, voice: string): ArrayBuffer {
    return nativeModule.synthesizeSpeech(text, voice);
  },
  notesSearch(query: string): string {
    return nativeModule.notesSearch(query);
  },
  miniAppsList(): string {
    return nativeModule.miniAppsList();
  },
  runSandboxTool(toolName: string, argsJson: string): string {
    return nativeModule.runSandboxTool(toolName, argsJson);
  },
  destroyEngine(): void {
    nativeModule.destroyEngine();
  }
};
