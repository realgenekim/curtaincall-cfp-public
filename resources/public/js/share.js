// Browser-native clipboard copy for the share strip (allowed JS: clipboard on
// user gesture). No DOM mutation, no state — fire and forget.
function copyShare(el) {
  navigator.clipboard.writeText(el.dataset.copy);
  if (window.showNotification) showNotification("Copied");
}

// A copied organizer preview link must be absolute when it leaves this page.
function copyLink(el) {
  navigator.clipboard.writeText(new URL(el.dataset.copyLink, window.location.origin).href);
  if (window.showNotification) showNotification("Copied public profile link");
}
