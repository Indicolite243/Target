import DOMPurify from 'dompurify'
import { marked } from 'marked'

/**
 * Render model output as GitHub-flavoured Markdown and remove unsafe HTML.
 * User messages remain plain text; this renderer is only used for assistant replies.
 */
export function renderSafeMarkdown(source) {
  // Models sometimes attach bold markers directly to Chinese punctuation, for
  // example: “这是**“重点”**说明”. CommonMark treats that delimiter as plain
  // text, so normalize simple paired markers before the standards parser runs.
  const normalized = String(source ?? '').replace(/\*\*([^\n*]+?)\*\*/g, '<strong>$1</strong>')
  const html = marked.parse(normalized, {
    async: false,
    breaks: true,
    gfm: true
  })

  return DOMPurify.sanitize(html)
}
