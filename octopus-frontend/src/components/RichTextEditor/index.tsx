import { useEffect, useState } from 'react';
import { EditorContent, useEditor, useEditorState } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { Markdown } from '@tiptap/markdown';
import { TableKit } from '@tiptap/extension-table';
import { marked } from 'marked';
import { Button, Input, Segmented, Space } from 'antd';
import './style.css';

// Prompt XML/HTML is literal model input, never executable markup or a hidden tag.
const literalMarkdown = marked.use({ extensions: [
  { name: 'literalHtmlBlock', level: 'block',
    tokenizer(source) {
      if (!source.startsWith('<')) return undefined;
      const end = source.indexOf('\n\n');
      const raw = end < 0 ? source : source.slice(0, end);
      return { type: 'paragraph', raw, text: raw, tokens: [{ type: 'text', raw, text: raw }] };
    },
  },
  { name: 'literalHtmlInline', level: 'inline', start: source => source.indexOf('<'),
    tokenizer(source) {
      const match = /^<[^>]*>/.exec(source);
      if (!match) return undefined;
      return { type: 'text', raw: match[0], text: match[0] };
    },
  },
] });

function preserveVariables(markdown: string) {
  return markdown.replace(/&lt;([^\n]*?)&gt;/g, '<$1>').replace(/\{\{[\s\S]*?\}\}/g, value => value.replace(/\\([_{}*`[\]])/g, '$1'));
}

type Props = { value?: string; onChange?: (value: string) => void; label?: string; readOnly?: boolean };

export default function RichTextEditor({ value = '', onChange, label = '文档正文', readOnly = false }: Props) {
  const [mode, setMode] = useState<string>('文档');
  const editor = useEditor({
    extensions: [StarterKit.configure({ link: { openOnClick: false }, heading: { levels: [1, 2, 3] } }),
      TableKit, Markdown.configure({ marked: literalMarkdown })],
    content: value, contentType: 'markdown', editable: !readOnly,
    editorProps: { attributes: { role: 'textbox', 'aria-label': label, 'aria-multiline': 'true' } },
    onUpdate: ({ editor }) => {
      const next = preserveVariables(editor.getMarkdown());
      onChange?.(next);
    },
  });
  useEditorState({ editor, selector: ({ editor }) => editor?.state });
  useEffect(() => {
    if (editor && preserveVariables(editor.getMarkdown()) !== value) {
      editor.commands.setContent(value, { contentType: 'markdown', emitUpdate: false });
    }
  }, [value, editor]);
  useEffect(() => { editor?.setEditable(!readOnly); }, [editor, readOnly]);
  if (!editor) return null;
  const button = (title: string, action: () => void, active = false) =>
    <Button size="small" type={active ? 'primary' : 'text'} aria-label={title}
      onMouseDown={event => event.preventDefault()} onClick={action}>{title}</Button>;
  return <div className={`rich-editor ${readOnly ? 'rich-editor-readonly' : ''}`}>
    <div className="rich-editor-toolbar">
      <Space size={2} wrap>
        {!readOnly && mode === '文档' && <>
          {button('标题', () => editor.chain().focus().toggleHeading({ level: 2 }).run(), editor.isActive('heading'))}
          {button('加粗', () => editor.chain().focus().toggleBold().run(), editor.isActive('bold'))}
          {button('斜体', () => editor.chain().focus().toggleItalic().run(), editor.isActive('italic'))}
          {button('列表', () => editor.chain().focus().toggleBulletList().run(), editor.isActive('bulletList'))}
          {button('编号', () => editor.chain().focus().toggleOrderedList().run(), editor.isActive('orderedList'))}
          {button('引用', () => editor.chain().focus().toggleBlockquote().run(), editor.isActive('blockquote'))}
          {button('代码块', () => editor.chain().focus().toggleCodeBlock().run(), editor.isActive('codeBlock'))}
          {button('表格', () => editor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run())}
          {button('撤销', () => editor.chain().focus().undo().run())}
          {button('重做', () => editor.chain().focus().redo().run())}
        </>}
      </Space>
      <Segmented size="small" aria-label={`${label}编辑模式`} value={mode} onChange={setMode} options={['文档', 'Markdown']} />
    </div>
    {mode === 'Markdown'
      ? <Input.TextArea aria-label={`${label} Markdown`} value={value} readOnly={readOnly}
          autoSize={{ minRows: 8, maxRows: 24 }} onChange={event => onChange?.(event.target.value)} />
      : <EditorContent editor={editor} />}
  </div>;
}
