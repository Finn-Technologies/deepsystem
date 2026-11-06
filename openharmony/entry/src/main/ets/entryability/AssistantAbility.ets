import { AbilityConstant, UIAbility, Want } from '@ohos.app.ability.UIAbility';
import { hilog } from '@ohos.hilog';
import { window } from '@ohos.window';
import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import { BusinessError } from '@ohos.base';

const TAG: string = 'AssistantAbility';

// Honest note: OpenHarmony reserves always-on hotword / voice-trigger detection
// for system-privileged assistants. A third-party app cannot register a global
// "Hey Finn" hotword. Invocation happens through the system assistant entry
// (long-press power / gesture) which launches this AssistantAbility, or via the
// in-app microphone. ContinuousTask below keeps an active voice session alive
// while the user interacts, but does not provide always-on listening.

export default class AssistantAbility extends UIAbility {
  private continuousTaskId: number = -1;

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(0x0000, TAG, 'onCreate');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(0x0000, TAG, 'onWindowStageCreate');
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(0x0000, TAG, 'Failed to load content. Cause: %{public}s', JSON.stringify(err));
      }
    });
    this.startContinuousTask();
  }

  onWindowStageDestroy(): void {
    this.stopContinuousTask();
  }

  onDestroy(): void {
    this.stopContinuousTask();
  }

  onForeground(): void {
    hilog.info(0x0000, TAG, 'onForeground');
  }

  onBackground(): void {
    hilog.info(0x0000, TAG, 'onBackground');
    // Keep the live session running briefly via continuous task.
  }

  private startContinuousTask(): void {
    try {
      const modes: Array<string> = ['audioRecording', 'audioPlayback'];
      backgroundTaskManager.startBackgroundRunning(this.context, modes, (err: BusinessError, id: number) => {
        if (err) {
          hilog.error(0x0000, TAG, 'startBackgroundRunning failed: %{public}s', JSON.stringify(err));
          return;
        }
        this.continuousTaskId = id;
        hilog.info(0x0000, TAG, 'continuous task started, id=%{public}d', id);
      });
    } catch (e) {
      hilog.error(0x0000, TAG, 'startContinuousTask exception: %{public}s', JSON.stringify(e));
    }
  }

  private stopContinuousTask(): void {
    if (this.continuousTaskId < 0) return;
    try {
      backgroundTaskManager.stopBackgroundRunning(this.context, this.continuousTaskId, (err: BusinessError) => {
        if (err) {
          hilog.error(0x0000, TAG, 'stopBackgroundRunning failed: %{public}s', JSON.stringify(err));
        }
      });
    } catch (e) {
      hilog.error(0x0000, TAG, 'stopContinuousTask exception: %{public}s', JSON.stringify(e));
    }
    this.continuousTaskId = -1;
  }
}
