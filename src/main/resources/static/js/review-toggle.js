// Only allow <details> toggle when clicking the review-toggle element.
// Without this, clicking anywhere in <summary> (title, cover, actions) toggles.
document.addEventListener('click', function(e) {
  var summary = e.target.closest('summary');
  if (summary && !e.target.closest('.review-toggle')) {
    e.preventDefault();
  }
});
