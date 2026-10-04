import MarkdownIt from 'markdown-it'

// 禁止原始 HTML 和图片加载，文件中的脚本或外部资源不能随预览自动执行。
const markdown = new MarkdownIt({ html: false, linkify: false }).disable('image')
const defaultValidateLink = markdown.validateLink.bind(markdown)
markdown.validateLink = url => /^(https?:|mailto:|#)/i.test(url) && defaultValidateLink(url)
markdown.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return renderer.renderToken(tokens, index, options)
}

export function renderMarkdown(content) {
  return markdown.render(content)
}
