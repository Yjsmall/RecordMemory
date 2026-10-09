# Recording swipe deletion

The library supports dragging a finished recording left to reveal a rounded
delete action. Swiping alone does not delete anything; the action opens a
confirmation dialog. Dragging right closes the action. Requested, recording,
paused and not-yet-recovered entries cannot be deleted.

Deletion appends `RecordingDeleted` and updates its projection in the same Room
transaction. The library excludes tombstones. The deleted projection keeps only
its audio filename and identity/version needed for cleanup and deduplication;
display metadata is cleared. Historical lifecycle events remain in the event
log. This is not a purge of all historical event metadata.

The command commits before deleting the finalized and partial private audio
files. Startup recovery retries pending cleanup without appending another event.
A cleanup failure is reported but does not disable recording unrelated entries.
Projection replay performs no file deletion, microphone access or network calls.
Rebuilt tombstones remain hidden, and cleanup runs through startup recovery only.

Validation: targeted recording reducer tests and the device repository test
cover deletion, duplicate commands, file cleanup, cleanup retry and rebuild.
Gesture tests cover reveal, closing, confirmation/cancellation and active-entry
protection; actual execution status should be reported separately from compiling
those tests.
