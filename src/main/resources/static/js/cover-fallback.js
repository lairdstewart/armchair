// Replace broken cover images with grey placeholder
document.addEventListener('error', function(e) {
  if (e.target.classList && e.target.classList.contains('book-cover')) {
    var placeholder = document.createElement('div');
    placeholder.className = 'book-cover-placeholder';
    placeholder.textContent = '?';
    e.target.parentNode.replaceChild(placeholder, e.target);
  }
}, true);
