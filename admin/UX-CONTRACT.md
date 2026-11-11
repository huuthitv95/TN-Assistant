# UX Contract

## Product context

Quản trị viên Việt Nam, quản lý thiết bị, cấu hình giọng nói, nội dung, sản phẩm và giao dịch sandbox. UI vi-VN; thời gian Asia/Ho_Chi_Minh, Gregorian. Mục tiêu WCAG 2.2 AA; native controls và keyboard không bị thay thế bởi thao tác con trỏ.

## Business-context sources

| Domain                       | Authoritative source                          | Source type         | Reviewed   |
| ---------------------------- | --------------------------------------------- | ------------------- | ---------- |
| Permissions, sessions        | ../api/CONTRACT.md auth/admin                 | API                 | 2026-10-02 |
| Lifecycle, deletion          | ../api/CONTRACT.md admin CRUD                 | API                 | 2026-10-02 |
| Billing                      | ../api/CONTRACT.md payments                   | API                 | 2026-10-02 |
| Provider credentials/privacy | ../implementation-plan.md; ../api/CONTRACT.md | Approved plan / API | 2026-10-02 |
| Scope and language           | ../implementation-plan.md                     | Approved plan       | 2026-10-02 |

Không thêm nội dung pháp lý mới. Nội dung consent là dữ liệu quản trị, không suy diễn thay người dùng.

## Visual contract

`DESIGN.md` ghi nhận nhận diện; `src/style.css` là runtime canonical (Model B). Có theme sáng, reduced motion và forced-colors. Token drift kiểm tra trong `tests/design.test.ts`.

## Canonical UI Map

| Capability     | Canonical owner               | Source of truth   | Allowed variants           | Verification                |
| -------------- | ----------------------------- | ----------------- | -------------------------- | --------------------------- |
| Select/Listbox | FormField in src/ui.tsx       | DESIGN + contract | native (OS popup accepted) | interaction tests/browser   |
| Date           | FormField                     | API               | typed ISO 8601             | validation test             |
| Form           | FormField + validate / Editor | contract          | create/edit/json           | tests/interactions.test.tsx |
| Scrollbar      | src/style.css global baseline | DESIGN            | table/dialog geometry      | browser computed style      |
| Toast          | App live-region               | contract          | success                    | interaction test            |
| CRUD           | EntityPage + Editor           | API               | return-to-list             | tests/interactions.test.tsx |
| Dialog         | Modal                         | contract          | editor/delete/unsaved      | interaction tests/browser   |

No bulk selection. New recurring behavior must use these owners.

## Dataset navigation

API lists return `{items}` without cursor/total; dashboard pages the returned dataset locally, 20 rows/page. It never claims server pagination. Search local and IME-safe, committed after 300ms, immediate Enter/clear, q/page in hash URL. Filtering/deletion clamps page to valid range. Lists preserve frame while loading/error/empty. Future server pagination requires contract change.

## Flow ledger

| Operation      | Trigger                                | Pending                   | Success                           | Failure recovery             | Source               |
| -------------- | -------------------------------------- | ------------------------- | --------------------------------- | ---------------------------- | -------------------- |
| Create/edit    | Form submit                            | disabled submit + spinner | close editor, refresh list, toast | retain values + inline error | API admin CRUD       |
| Delete         | row action + object/consequence dialog | disable actions           | close dialog, refresh list        | keep dialog, retry           | API DELETE           |
| Search         | input/Enter/clear                      | debounce local filter     | URL q/page                        | no network mutation          | list API             |
| Sandbox settle | explicit status confirmation           | disable submit            | refresh transaction               | inline error, keep selection | API payments         |
| Config         | explicit save                          | pessimistic               | returned object + toast           | keep edits                   | API config/providers |
| Link           | code + user ID                         | disabled submit           | clear inputs + toast              | retain input                 | API admin/link       |

## Navigation and overlays

Routes set Vietnamese document.title. Hash routes preserve q/page and navigation anchors. 403 stays an error with navigation available. Destructive confirmation names object and consequence; least destructive action autofocus. Native dialog owns focus trap, inert background, Escape/focus restoration. Toast bottom-right, five seconds, stable live region. Form changes remain in memory, and in-app navigation/unload warns before loss; secrets not persisted.

## Async, validation and resilience

Pessimistic mutations; prevent double submit, no automatic mutation retry. GETs abort on route/refresh/unmount. Refresh-token calls coalesce; only one access retry; failed session clears tokens and returns login. Access/refresh tokens live only in module memory, never local/session storage. Provider secrets never entered directly, shown, toasted, or persisted; config only uses ENV/file references. Offline failure is explicit; safe inputs retained. No ETag contract exists, so no promise of multi-admin conflict protection.

Validation owns noValidate/errors/first-invalid focus. Fields use associated labels and aria-invalid/describedby. Native select popup accepted across Windows Chrome/Edge; typed ISO dates avoid locale-specific native pickers. Password masked with show/hide button, paste/password managers allowed. Config JSON has schema-preserving object validation, HTTP(S)-only navigation links.

## Verification

`npm run typecheck`, `npm test`, `npm run format:check`, `npm run build`, premium strict audit. Tests use clearly named TEST fixtures, including login authorization, create failure recovery, successful CRUD/refetch, deletion confirmation and search paging. Runtime browser checks delegated to root: desktop and 390px, keyboard/dialog focus, native select open, API-error/empty/loading, reduced motion. Unit tests do not prove all browser accessibility claims.
