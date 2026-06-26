# BibleNotes - BETA 📖

*Last updated: 21/6/26*

A Bible reading and note-taking app for Android.

I started this project because there was not a dynamic Bible note-taking app on the Play Store that did what I wanted, and my phone is too slow for some Bible reading apps, such as YouVersion. So I made one myself 😁

In the future, if I become a missionary or apologist, I want to be able to quickly open my notes and share what I know with clear references.

---

## What it does

* **Bible** — Sorted by Old and New Testament, with KJV available by default and more translations available through the YouVersion Platform API.
* **Notes** — Notes can highlight Bible references. Tapping a reference will show the related verse or verses.
* **Documents** — Other documents related to Christianity.
* **Theme** — Light and Dark Theme

---

## Contents

* [Bible](#bible)
* [Notes](#notes)
* [Documents](#documents)
* [Built with](#built-with)
* [Running it yourself](#running-it-yourself)
* [Todo](#todo)
* [Notes on copyright](#notes-on-copyright)

---

## Bible

Browse the full Bible, split by Old and New Testament. You can swipe through books as cards, designed to feel like turning the pages of a real book, or switch to a plain list for faster searching. You can toggle between them with the **Switch Mode** button at the bottom.

The cards are currently just placeholders for book cover designs that I have not made yet. I want to create beautiful cards or covers for each book that represent the main story or theme of that book.

Tapping a book opens its chapter grid. Tapping a chapter opens the verses, like a normal Bible app.

### Translations

A translation picker sits at the top of the reader. Tap it to see available translations and switch between them. The current chapter reloads in the new translation straight away.

The King James Version is bundled in the app and works without internet. NIV is fetched through the YouVersion Platform and cached on-device as you read, so any chapter you have opened before will load offline next time too.

| Translation | Source                  | Offline          |
| ----------- | ----------------------- | ---------------- |
| KJV         | Bundled SQLite database | Always           |
| NIV         | YouVersion Platform API | After first read |

More translations are planned.

---

## Notes

The Notes section shows a list of notes saved in the database, if any exist.

You can create a new note with a title. The title could be the topic you are writing about or the date and pastor's name if you are taking notes during a sermon.

Inside a note, you can type a Bible reference using the book name, chapter, and optional verse range. The app detects the reference and highlights it. When you tap the highlighted reference, it shows the related verse or verses from the Bible.

Examples:

| Reference typed in note | Result                                                   |
| ----------------------- | -------------------------------------------------------- |
| `john1:`                | Shows John chapter 1                                     |
| `john1:1`               | Shows John chapter 1, verse 1                            |
| `john1:1-2`             | Shows John chapter 1, verses 1 to 2                      |
| `jn1:1`                 | Resolves as John 1:1 if `jn` is set as an alias for John |

References are flexible. You can use full book names or set up your own shortcuts in Settings. For example, you can map `jn` to John, then type `jn1:1`, and the app will resolve it correctly as John 1:1.

Aliases are stored per user, so you can shape the shorthand around whatever feels fastest to type.

You can also export and import notes. This is useful for backing up notes before app updates or sharing notes with others.

---

## Documents

A separate tab for longer reference texts that do not fit the verse-by-verse model. The first document included is the **Catechism of the Catholic Church**.

The built-in viewer:

* Swipe up and down to flip between pages.
* Pinch to zoom in on dense text.
* Tap the page indicator, such as `42/628`, to jump straight to a page.

The document list is extensible, and more references will be added over time.

---

## Built with

* Kotlin + coroutines
* View-based Android UI, including Fragments, RecyclerView, and ViewPager2
* SQLite for the bundled KJV, notes, aliases, and the on-device chapter cache
* Android's built-in `PdfRenderer` + [PhotoView](https://github.com/Baseflow/PhotoView) for the document viewer
* [YouVersion Platform SDK](https://developers.youversion.com) for licensed translations

---

## Running it yourself

KJV and the Catechism work out of the box. For NIV and other remote translations, you will need a free YouVersion Platform key:

1. Sign up at [platform.youversion.com](https://platform.youversion.com) and create an app.

2. Enable the translations you want. NIV has to be turned on explicitly in the dashboard.

3. Add your key to `local.properties` in the project root:

   ```properties
   youversion.appKey=YOUR_KEY_HERE
   ```

4. Open the project in Android Studio, sync Gradle, and run.

---

## Todo

### Main

* Design the book covers.
* Better Design note layouts

### Small

* Show a proper empty state when a remote chapter fails to load.

### Could do

* Add more translations.
* Add more documents.
* Add auto-save for notes.

---

## Notes on copyright

KJV is public domain. Modern translations like NIV are copyrighted and accessed at runtime through the YouVersion Platform under its terms. They are fetched on demand and cached only on the device. No copyrighted scripture is stored in this repository, and remote access is for non-commercial use under the platform's terms.

The Catechism PDF is included as a reference document. Check the source or edition you are using for any redistribution terms that apply.

---

Logs:
11/06/26: added light theme with some UI/UX improvements
13/06/26: UI/UX up-to-date with the design, with some adjustments
16/06/26: attempted formatting NIV

---
*Personal project — built to actually use.*
