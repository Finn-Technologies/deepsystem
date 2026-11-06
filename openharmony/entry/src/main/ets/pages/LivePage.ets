import router from '@ohos.router';
import { finn } from '../common/finn_napi';

@Entry
@Component
struct LivePage {
  @State status: string = 'Idle';
  @State transcript: string = '';

  build() {
    Column({ space: 12 }) {
      Text('Live voice + camera').fontSize(20).fontWeight(FontWeight.Bold)
      Text(this.status).fontSize(14)
      Row({ space: 8 }) {
        Button('Start').onClick(() => { this.status = 'Listening'; })
        Button('Stop').onClick(() => { this.status = 'Idle'; })
        Button('Barge-in').onClick(() => { this.status = 'Thinking'; })
      }
      // Camera preview placeholder (XComponent would host native preview)
      Column() {
        Text('Camera preview').fontColor('#888888')
      }.width(320).height(240).backgroundColor('#000000').borderRadius(8)
      Text(this.transcript).fontSize(12).margin({ top: 8 })
      Button('Back').onClick(() => router.back())
    }.width('100%').height('100%').padding(16)
  }
}
