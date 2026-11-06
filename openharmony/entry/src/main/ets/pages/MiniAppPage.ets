import router from '@ohos.router';
import { finn } from '../common/finn_napi';

@Entry
@Component
struct MiniAppPage {
  @State apps: string = '';
  @State runResult: string = '';

  aboutToAppear() {
    this.apps = finn.miniAppsList();
  }

  build() {
    Column({ space: 12 }) {
      Text('MiniApps (sandboxed)').fontSize(20).fontWeight(FontWeight.Bold)
      Scroll() { Text(this.apps).fontSize(12) }.height('40%')
      Button('Run sandbox tool').onClick(() => {
        this.runResult = finn.runSandboxTool('echo', '{"text":"hello"}');
      })
      Text(this.runResult).fontSize(12)
      Button('Back').onClick(() => router.back())
    }.width('100%').height('100%').padding(16)
  }
}
