package com.networkprinter.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.networkprinter.app.databinding.ItemPrinterBinding

/**
 * RecyclerView adapter for displaying discovered printers
 */
class PrinterAdapter(
    private val onPrinterSelected: (PrinterInfo) -> Unit
) : ListAdapter<PrinterInfo, PrinterAdapter.PrinterViewHolder>(PrinterDiffCallback()) {

    private var selectedPrinterId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PrinterViewHolder {
        val binding = ItemPrinterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PrinterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PrinterViewHolder, position: Int) {
        val printer = getItem(position)
        holder.bind(printer, printer.id == selectedPrinterId)
    }

    fun selectPrinter(printerId: String) {
        val oldSelectedId = selectedPrinterId
        selectedPrinterId = printerId
        
        // Refresh old and new selected items
        currentList.forEachIndexed { index, printer ->
            if (printer.id == oldSelectedId || printer.id == printerId) {
                notifyItemChanged(index)
            }
        }
    }

    fun getSelectedPrinter(): PrinterInfo? {
        return currentList.find { it.id == selectedPrinterId }
    }

    inner class PrinterViewHolder(
        private val binding: ItemPrinterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(printer: PrinterInfo, isSelected: Boolean) {
            binding.apply {
                textPrinterName.text = printer.name
                textPrinterAddress.text = printer.address

                // Set status indicator
                val statusColor = when (printer.status) {
                    PrinterStatus.ONLINE -> R.color.status_online
                    PrinterStatus.OFFLINE -> R.color.status_offline
                    PrinterStatus.BUSY -> R.color.status_busy
                    PrinterStatus.ERROR -> R.color.status_offline
                    PrinterStatus.UNKNOWN -> R.color.text_hint
                }

                statusIndicator.backgroundTintList = ContextCompat.getColorStateList(
                    root.context,
                    statusColor
                )

                textStatus.text = when (printer.status) {
                    PrinterStatus.ONLINE -> root.context.getString(R.string.printer_status_online)
                    PrinterStatus.OFFLINE -> root.context.getString(R.string.printer_status_offline)
                    PrinterStatus.BUSY -> root.context.getString(R.string.printer_status_busy)
                    else -> ""
                }

                textStatus.setTextColor(ContextCompat.getColor(root.context, statusColor))

                // Set selection state
                cardPrinter.isChecked = isSelected
                cardPrinter.strokeColor = if (isSelected) {
                    ContextCompat.getColor(root.context, R.color.primary)
                } else {
                    ContextCompat.getColor(root.context, R.color.divider)
                }

                // Click listener
                root.setOnClickListener {
                    selectPrinter(printer.id)
                    onPrinterSelected(printer)
                }
            }
        }
    }

    /**
     * DiffUtil callback for efficient list updates
     */
    class PrinterDiffCallback : DiffUtil.ItemCallback<PrinterInfo>() {
        override fun areItemsTheSame(oldItem: PrinterInfo, newItem: PrinterInfo): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PrinterInfo, newItem: PrinterInfo): Boolean {
            return oldItem == newItem
        }
    }
}
