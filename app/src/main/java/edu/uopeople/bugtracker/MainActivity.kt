package edu.uopeople.bugtracker

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val model: EditorViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 40, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String) = TextView(this).also { it.text = text; root.addView(it) }
        fun field(hint: String, id: Int) = EditText(this).also { it.hint = hint; it.id = id; root.addView(it) }
        label("Bug Tracker")
        val title = field("Issue title", 1001)
        val description = field("Description", 1002).apply { minLines = 2 }
        val priority = Spinner(this).also { root.addView(it) }
        val status = Spinner(this).also { root.addView(it) }
        val priorities = listOf("LOW", "MEDIUM", "HIGH")
        val statuses = listOf("OPEN", "IN_PROGRESS", "CLOSED")
        priority.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, priorities)
        status.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, statuses)
        val save = Button(this).also { it.text = "Save issue"; it.isEnabled = false; root.addView(it) }
        val fresh = Button(this).also { it.text = "New draft"; root.addView(it) }
        val sync = Button(this).also { it.text = "Sync now"; root.addView(it) }
        val info = label("")
        val syncInfo = label("Changes are stored on this device first.")
        val problems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(problems)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(list)
        var binding = false
        fun changed() {
            if (!binding && model.ready.value) model.change(model.draft.value.copy(
                title = title.text.toString(), description = description.text.toString(),
                priority = priorities[priority.selectedItemPosition], status = statuses[status.selectedItemPosition]))
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = changed()
        }
        title.addTextChangedListener(watcher); description.addTextChangedListener(watcher)
        val selected = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = changed()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        priority.onItemSelectedListener = selected; status.onItemSelectedListener = selected
        save.setOnClickListener { model.submit() }
        fresh.setOnClickListener { model.change(Draft()) }
        sync.setOnClickListener { requestSync(this) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.ready.collect { save.isEnabled = it } }
                launch { model.draft.collect { d ->
                    binding = true
                    if (title.text.toString() != d.title) title.setText(d.title)
                    if (description.text.toString() != d.description) description.setText(d.description)
                    priority.setSelection(priorities.indexOf(d.priority).coerceAtLeast(0))
                    status.setSelection(statuses.indexOf(d.status).coerceAtLeast(0))
                    binding = false
                } }
                launch { model.message.collect { info.text = it } }
                launch { model.repo.dao.syncState().catch { syncInfo.text = "Cannot read sync status." }
                    .collect { if (it != null) syncInfo.text = it.message } }
                launch { model.repo.dao.problems().catch { info.text = "Cannot read conflicts." }.collect { issues ->
                    problems.removeAllViews()
                    for (issue in issues) {
                        problems.addView(TextView(this@MainActivity).apply { text = "${issue.title}: ${issue.error}" })
                        for (keep in listOf(true, false)) problems.addView(Button(this@MainActivity).apply {
                            text = if (keep) "Keep my change" else "Use server version"
                            setOnClickListener { model.resolve(issue.id, keep) }
                        })
                    }
                } }
                launch { model.repo.dao.observe().catch { info.text = "Cannot read saved issues." }.collect { issues ->
                    list.removeAllViews()
                    for (issue in issues) {
                        list.addView(TextView(this@MainActivity).apply {
                            text = "${issue.title}\n${issue.description}\n${issue.priority} | ${issue.status}\n" +
                                DateFormat.getDateTimeInstance().format(Date(issue.createdAt)) +
                                if (issue.dirty) "\nPending sync" else "\nSynced"
                        })
                        list.addView(Button(this@MainActivity).apply { text = "Edit"; setOnClickListener { model.edit(issue) } })
                        list.addView(Button(this@MainActivity).apply { text = "Delete"; setOnClickListener { model.delete(issue.id) } })
                    }
                } }
            }
        }
    }
    override fun onResume() { super.onResume(); requestSync(this) }
}
