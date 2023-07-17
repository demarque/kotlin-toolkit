/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.bookshelf

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.readium.r2.shared.util.Url
import org.readium.r2.testapp.R
import org.readium.r2.testapp.databinding.FragmentBookshelfBinding
import org.readium.r2.testapp.domain.model.Book
import org.readium.r2.testapp.opds.GridAutoFitLayoutManager
import org.readium.r2.testapp.reader.ReaderActivityContract
import org.readium.r2.testapp.utils.viewLifecycle

class BookshelfFragment : Fragment() {

    private val bookshelfViewModel: BookshelfViewModel by activityViewModels()
    private lateinit var bookshelfAdapter: BookshelfAdapter
    private lateinit var appStoragePickerLauncher: ActivityResultLauncher<String>
    private lateinit var sharedStoragePickerLauncher: ActivityResultLauncher<Array<String>>
    private var binding: FragmentBookshelfBinding by viewLifecycle()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentBookshelfBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bookshelfViewModel.channel.receive(viewLifecycleOwner) { handleEvent(it) }

        bookshelfAdapter = BookshelfAdapter(
            onBookClick = { book -> book.id?.let { bookshelfViewModel.openPublication(it, requireActivity()) } },
            onBookLongClick = { book -> confirmDeleteBook(book) }
        )

        appStoragePickerLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let {
                    binding.bookshelfProgressBar.visibility = View.VISIBLE
                    bookshelfViewModel.importPublicationFromUri(it)
                }
            }

        sharedStoragePickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)
                    binding.bookshelfProgressBar.visibility = View.VISIBLE
                    bookshelfViewModel.addSharedStoragePublication(it)
                }
            }

        binding.bookshelfBookList.apply {
            setHasFixedSize(true)
            layoutManager = GridAutoFitLayoutManager(requireContext(), 120)
            adapter = bookshelfAdapter
            addItemDecoration(
                VerticalSpaceItemDecoration(
                    10
                )
            )
        }

        bookshelfViewModel.books.observe(viewLifecycleOwner) {
            bookshelfAdapter.submitList(it)
        }

        // FIXME embedded dialogs like this are ugly
        binding.bookshelfAddBookFab.setOnClickListener {
            var selected = 0
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.add_book))
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                    dialog.cancel()
                }
                .setPositiveButton(getString(R.string.ok)) { _, _ ->

                    when (selected) {
                        0 -> appStoragePickerLauncher.launch("*/*")
                        1 -> sharedStoragePickerLauncher.launch(arrayOf("*/*"))
                        else -> {
                            val urlEditText = EditText(requireContext())
                            val urlDialog = MaterialAlertDialogBuilder(requireContext())
                                .setTitle(getString(R.string.add_book))
                                .setMessage(R.string.enter_url)
                                .setView(urlEditText)
                                .setNegativeButton(R.string.cancel) { dialog, _ ->
                                    dialog.cancel()
                                }
                                .setPositiveButton(getString(R.string.ok), null)
                                .show()
                            urlDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val url = Url(urlEditText.text.toString())
                                if (url == null || !URLUtil.isValidUrl(urlEditText.text.toString())) {
                                    urlEditText.error = getString(R.string.invalid_url)
                                    return@setOnClickListener
                                }

                                binding.bookshelfProgressBar.visibility = View.VISIBLE
                                bookshelfViewModel.addRemotePublication(url)
                                urlDialog.dismiss()
                            }
                        }
                    }
                }
                .setSingleChoiceItems(R.array.documentSelectorArray, 0) { _, which ->
                    selected = which
                }
                .show()
        }
    }

    private fun handleEvent(event: BookshelfViewModel.Event) {
        val message =
            when (event) {
                is BookshelfViewModel.Event.ImportPublicationSuccess ->
                    getString(R.string.import_publication_success)

                is BookshelfViewModel.Event.ImportPublicationError -> {
                    event.errorMessage
                }

                is BookshelfViewModel.Event.OpenPublicationError -> {
                    event.errorMessage
                }

                is BookshelfViewModel.Event.LaunchReader -> {
                    val intent = ReaderActivityContract().createIntent(requireContext(), event.arguments)
                    startActivity(intent)
                    null
                }
            }
        binding.bookshelfProgressBar.visibility = View.GONE
        message?.let {
            Snackbar.make(
                requireView(),
                it,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    class VerticalSpaceItemDecoration(private val verticalSpaceHeight: Int) :
        RecyclerView.ItemDecoration() {

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.bottom = verticalSpaceHeight
        }
    }

    private fun deleteBook(book: Book) {
        bookshelfViewModel.deletePublication(book)
    }

    private fun confirmDeleteBook(book: Book) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_delete_book_title))
            .setMessage(getString(R.string.confirm_delete_book_text))
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.cancel()
            }
            .setPositiveButton(getString(R.string.delete)) { dialog, _ ->
                deleteBook(book)
                dialog.dismiss()
            }
            .show()
    }
}
