import router from '@ohos.router';
import { finn } from '../common/finn_napi';

@Entry
@Component
struct Index {
  @State message: string = 'Finn DeepSystem';
  @State reply: string = '';

  build() {
    Column({ space: 12 }) {
      Text(this.message).fontSize(24).fontWeight(FontWeight.Bold).margin({ top: 24 })
      Button('Live voice/camera').onClick(() => router.pushUrl({ url: 'pages/LivePage' }))
      Button('Notes (FTS5)').onClick(() => router.pushUrl({ url: 'pages/NotesPage' }))
      Button('MiniApps').onClick(() => router.pushUrl({ url: 'pages/MiniAppPage' }))
      TextInput({ placeholder: 'Ask Finn...' }).onChange((v: string) => {
        // streaming chat via NAPI
      }).width('90%')
      Button('Send').onClick(() => {
        finn.chatStream('Hello Finn', (t: string) => { this.reply += t; });
      })
      Text(this.reply).fontSize(14).margin({ top: 8 })
    }.width('100%').height('100%').padding(16)
  }
}
