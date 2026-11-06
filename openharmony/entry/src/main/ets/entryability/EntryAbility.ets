import { AbilityConstant, UIAbility, Want } from '@ohos.app.ability.UIAbility';
import { hilog } from '@ohos.hilog';
import { window } from '@ohos.window';
import { finn } from '../common/finn_napi';

const TAG: string = 'EntryAbility';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(0x0000, TAG, 'onCreate');
    finn.initEngine('/data/storage/el2/base/haps/entry/files/model.gguf', 2048, 4);
  }

  onDestroy(): void {
    hilog.info(0x0000, TAG, 'onDestroy');
    finn.destroyEngine();
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(0x0000, TAG, 'onWindowStageCreate');
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(0x0000, TAG, 'Failed to load content. Cause: %{public}s', JSON.stringify(err));
      }
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(0x0000, TAG, 'onWindowStageDestroy');
  }

  onForeground(): void {
    hilog.info(0x0000, TAG, 'onForeground');
  }

  onBackground(): void {
    hilog.info(0x0000, TAG, 'onBackground');
  }
}
