import router from '@ohos.router';
import { finn } from '../common/finn_napi';

@Entry
@Component
struct NotesPage {
  @State query: string = '';
  @State results: string = '';

  build() {
    Column({ space: 12 }) {
      Text('Notes (FTS5)').fontSize(20).fontWeight(FontWeight.Bold)
      Search({ value: this.query, placeholder: 'Search notes...' })
        .onSubmit((value: string) => {
          this.query = value;
          this.results = finn.notesSearch(value);
        })
        .width('90%')
      Scroll() {
        Text(this.results).fontSize(12)
      }.height('60%')
      Button('Back').onClick(() => router.back())
    }.width('100%').height('100%').padding(16)
  }
}
