import React, { useRef, useEffect, forwardRef, useImperativeHandle, useMemo } from 'react';
import { WebView } from 'react-native-webview';
import { View, StyleSheet } from 'react-native';

interface Props {
    initialContent?: string;
    onChange?: (content: string) => void;
    isDarkMode?: boolean;
}

export interface RichEditorRef {
    format: (command: string, value?: string) => void;
    insertHtml: (html: string) => void;
    setContent: (content: string) => void;
}

const getHtmlTemplate = (isDarkMode: boolean) => {
    const colors = {
        bgColor: isDarkMode ? '#111827' : '#ffffff',
        textColor: isDarkMode ? '#ffffff' : '#000000',
        linkColor: isDarkMode ? '#60A5FA' : '#2563EB',
        borderColor: isDarkMode ? '#374151' : '#E5E7EB',
        headerBg: isDarkMode ? '#1F2937' : '#F3F4F6',
        blockquoteColor: isDarkMode ? '#9CA3AF' : '#4B5563',
    };

    return `
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<style>
  body { 
    background-color: ${colors.bgColor}; 
    color: ${colors.textColor}; 
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; 
    padding: 16px; 
    margin: 0; 
  }
  #editor { 
    min-height: 90vh; 
    outline: none; 
    font-size: 18px; 
    line-height: 1.5;
  }
  a { color: ${colors.linkColor}; text-decoration: underline; }
  table { border-collapse: collapse; width: 100%; margin: 10px 0; border: 1px solid ${colors.borderColor}; }
  th, td { border: 1px solid ${colors.borderColor}; padding: 8px; text-align: left; }
  th { background-color: ${colors.headerBg}; font-weight: bold; }
  blockquote { border-left: 4px solid ${colors.borderColor}; margin: 0; padding-left: 10px; color: ${colors.blockquoteColor}; font-style: italic; }
  ul, ol { padding-left: 20px; }
  img { max-width: 100%; height: auto; border-radius: 8px; }
  b, strong { font-weight: bold; }
  i, em { font-style: italic; }
</style>
<style id="theme-style"></style>
</head>
<body>
<div id="editor" contenteditable="true"></div>
<script>
  const editor = document.getElementById('editor');
  
  // Debounce function
  function debounce(func, wait) {
    let timeout;
    return function(...args) {
      clearTimeout(timeout);
      timeout = setTimeout(() => func.apply(this, args), wait);
    };
  }

  const notifyChange = debounce(() => {
    window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'content', data: editor.innerHTML }));
  }, 500);

  editor.addEventListener('input', notifyChange);

  // Handle messages from RN
  function handleMessage(event) {
    try {
        const msg = JSON.parse(event.data);
        if (msg.type === 'format') {
            document.execCommand(msg.command, false, msg.value);
        } else if (msg.type === 'set-content') {
            editor.innerHTML = msg.data;
        } else if (msg.type === 'insert-html') {
            document.execCommand('insertHTML', false, msg.data);
        } else if (msg.type === 'set-theme') {
            document.body.style.backgroundColor = msg.colors.bgColor;
            document.body.style.color = msg.colors.textColor;
            const style = document.getElementById('theme-style');
            if (style) {
                style.innerHTML = \`
                    a { color: \${msg.colors.linkColor}; text-decoration: underline; }
                    table, th, td { border: 1px solid \${msg.colors.borderColor}; }
                    th { background-color: \${msg.colors.headerBg}; }
                    blockquote { border-left: 4px solid \${msg.colors.borderColor}; color: \${msg.colors.blockquoteColor}; }
                \`;
            }
        }
    } catch (e) {
        // console.error(e);
    }
  }

  // iOS
  window.addEventListener('message', handleMessage);
  // Android
  document.addEventListener('message', handleMessage);

</script>
</body>
</html>
`;
};

export const RichEditor = forwardRef<RichEditorRef, Props>(({ initialContent = '', onChange, isDarkMode = true }, ref) => {
    const webviewRef = useRef<WebView>(null);
    const source = useMemo(() => ({ html: getHtmlTemplate(isDarkMode) }), []); // Initial load only

    useImperativeHandle(ref, () => ({
        format: (command, value) => {
            const script = `
                handleMessage({ data: JSON.stringify({ type: 'format', command: '${command}', value: '${(value || '').replace(/'/g, "\\'")}' }) });
            `;
            webviewRef.current?.injectJavaScript(script);
        },
        insertHtml: (html) => {
            const script = `
                handleMessage({ data: JSON.stringify({ type: 'insert-html', data: '${html.replace(/'/g, "\\'").replace(/\n/g, "\\n")}' }) });
            `;
            webviewRef.current?.injectJavaScript(script);
        },
        setContent: (content) => {
            const script = `
                handleMessage({ data: JSON.stringify({ type: 'set-content', data: '${content.replace(/'/g, "\\'").replace(/\n/g, "\\n")}' }) });
            `;
            webviewRef.current?.injectJavaScript(script);
        }
    }));

    const updateTheme = (dark: boolean) => {
        const colors = {
            bgColor: dark ? '#111827' : '#ffffff',
            textColor: dark ? '#ffffff' : '#000000',
            linkColor: dark ? '#60A5FA' : '#2563EB',
            borderColor: dark ? '#374151' : '#E5E7EB',
            headerBg: dark ? '#1F2937' : '#F3F4F6',
            blockquoteColor: dark ? '#9CA3AF' : '#4B5563',
        };

        const script = `
            handleMessage({ data: JSON.stringify({ type: 'set-theme', colors: ${JSON.stringify(colors)} }) });
            true;
        `;
        webviewRef.current?.injectJavaScript(script);
    };

    useEffect(() => {
        updateTheme(isDarkMode);
    }, [isDarkMode]);

    const handleMessage = (event: any) => {
        try {
            const data = JSON.parse(event.nativeEvent.data);
            if (data.type === 'content' && onChange) {
                onChange(data.data);
            }
        } catch (e) {
            // ignore
        }
    };

    // Initial content injection
    const injectedJS = `
        setTimeout(() => {
            const editor = document.getElementById('editor');
            if (editor && !editor.innerHTML) {
                editor.innerHTML = '${initialContent.replace(/'/g, "\\'").replace(/\n/g, "\\n")}';
            }
        }, 100);
        true;
    `;

    return (
        <View style={{ flex: 1, backgroundColor: isDarkMode ? '#111827' : '#ffffff' }}>
            <WebView
                ref={webviewRef}
                originWhitelist={['*']}
                source={source}
                onMessage={handleMessage}
                injectedJavaScript={injectedJS}
                onLoad={() => updateTheme(isDarkMode)}
                style={{ flex: 1, backgroundColor: 'transparent' }}
                containerStyle={{ backgroundColor: isDarkMode ? '#111827' : '#ffffff' }}
                scrollEnabled={true}
                hideKeyboardAccessoryView={true}
                keyboardDisplayRequiresUserAction={false}
            />
        </View>
    );
});
