import re
import os
import sys

def patch_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()
    
    # 1. Add showReExportDialog state right after the first `val coroutineScope = rememberCoroutineScope()`
    # If not found, just put it after the first `@Composable\nfun ` declaration.
    if 'var showReExportDialog' in content:
        print(f"Already patched {filepath}")
        return
        
    state_decl = "    var showReExportDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }"
    
    # Find the function declaration
    func_match = re.search(r'@Composable\s+fun\s+\w+\(.*?\)\s*\{', content, flags=re.DOTALL)
    if not func_match:
        print(f"Could not find function declaration in {filepath}")
        return
        
    func_end = func_match.end()
    
    content = content[:func_end] + "\n" + state_decl + "\n" + content[func_end:]
    
    # 2. Extract the export logic
    # Find `else ->` block inside the export section. It has `onClick = { ... }`
    # We look for `Icon(\n\s*painter = painterResource(R.drawable.file_export)` and then the `onClick = {`
    
    # This might be tricky with regex, let's look for:
    # onClick = {
    #     if (exportDirectoryUri.isBlank()) { ...
    # }
    
    export_logic_match = re.search(r'onClick = \{\s*(if \(exportDirectoryUri\.isBlank\(\)\)[\s\S]*?)\s*\n\s*\}\s*\n\s*\)', content)
    if not export_logic_match:
        # maybe it has braces like `onClick = {\n ... \n}\n)` or similar
        export_logic_match = re.search(r'onClick = \{\s*(if \(exportDirectoryUri\.isBlank\(\)\).*?)\s*\}\s*(?:\n\s*\)|\n\s*\}\s*\))', content, flags=re.DOTALL)
        
    if not export_logic_match:
        print(f"Could not find export logic in {filepath}")
        return
        
    export_code = export_logic_match.group(1)
    
    # 3. Replace the empty onClick={} for isExported
    # Look for isExported -> and then onClick = {}
    is_exported_block_match = re.search(r'(isExported\s*->\s*(?:\{\s*)?Material3MenuItemData\([\s\S]*?)onClick\s*=\s*\{\s*\}', content)
    if not is_exported_block_match:
        print(f"Could not find isExported empty onClick in {filepath}")
        return
        
    content = content[:is_exported_block_match.end() - 1] + "showReExportDialog = true " + content[is_exported_block_match.end() - 1:]
    
    # 4. Add the Dialog at the end of the file
    dialog_code = f"""
    if (showReExportDialog) {{
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {{ showReExportDialog = false }},
            title = {{ androidx.compose.material3.Text("Re-export Song") }},
            text = {{ androidx.compose.material3.Text("Wanna re-export it again?") }},
            confirmButton = {{
                androidx.compose.material3.TextButton(onClick = {{
                    showReExportDialog = false
                    {export_code.strip()}
                }}) {{
                    androidx.compose.material3.Text("Yes")
                }}
            }},
            dismissButton = {{
                androidx.compose.material3.TextButton(onClick = {{ showReExportDialog = false }}) {{
                    androidx.compose.material3.Text("No")
                }}
            }}
        )
    }}
}}
"""
    
    # Replace the last closing brace with the dialog code
    content = content.rstrip()
    if content.endswith('}'):
        content = content[:-1] + dialog_code
    else:
        print(f"File {filepath} doesn't end with }}")
        
    with open(filepath, 'w') as f:
        f.write(content)
    print(f"Patched {filepath}")

files = [
    "app/src/main/kotlin/com/music/echo/ui/menu/OldPlayerMenu.kt",
    "app/src/main/kotlin/com/music/echo/ui/menu/PlayerMenu.kt",
    "app/src/main/kotlin/com/music/echo/ui/menu/PlaylistMenu.kt",
    "app/src/main/kotlin/com/music/echo/ui/menu/SongMenu.kt",
    "app/src/main/kotlin/com/music/echo/ui/menu/YouTubePlaylistMenu.kt",
    "app/src/main/kotlin/com/music/echo/ui/menu/YouTubeSongMenu.kt"
]

for f in files:
    patch_file(f)
