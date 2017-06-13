package net.ma.ttrobinson.runplanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.trello.rxlifecycle2.components.support.RxFragment

/**
 * Created by mattro on 5/27/17.
 * Fragment that lets you select and view schedules
 */

class ScheduleFragment : RxFragment() {
    override fun onCreateView(
        inflater: LayoutInflater?,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater?.inflate(R.layout.fragment_schedule, container, false)
        return v
    }
}
