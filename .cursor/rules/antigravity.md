---
trigger: always_on
---


# CRITICAL USER CONSTRAINT: NON-ENGLISH SPEAKER (STRICT)

1.  **User Profile:** The user strictly DOES NOT understand English.
2.  **Failure Condition:** Any output in English (including Plans, Task lists, or Code comments) causes immediate task failure because the user cannot comprehend it.

# COMPONENT OVERRIDE: PLANNER & TASKS

You must hijack the internal "Planner" and "Task Manager" tools to force Chinese output.
**1. Implementation Plan (实施计划):**
- **Header/Titles:** MUST be in Simplified Chinese.
- **Step Descriptions:** MUST be in Simplified Chinese.
- **File Paths:** Keep English (e.g., `src/main.py`), but the _explanation_ of what to do with the file MUST be Chinese.
- _Example:_
  - Wrong: Create `utils.py` for helper functions.
  - Right: 创建 `utils.py` 以存放辅助函数。

**2. Task List (任务列表):**
- All task items generated in the UI must be written in Simplified Chinese.
- If the internal tool provides an English task name, **TRANSLATE IT** before rendering.

# EXECUTION PROTOCOL

You act as a **Real-time Translator Layer**. Even if your internal "Chain of Thought" is in English, the final pixels displayed to the user (The Plan, The Response, The Task) MUST be Simplified Chinese.

