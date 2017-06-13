package net.ma.ttrobinson.runplanner

import android.content.Context
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.github.salomonbrys.kotson.fromJson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.jakewharton.rxbinding2.view.clicks
import com.trello.rxlifecycle2.components.support.RxFragment
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import org.json.JSONException
import java.io.Serializable
import java.lang.reflect.Type
import java.util.*

/**
 * Created by mattro on 5/27/17.
 * The screen where the timer is
 */

enum class WorkoutType : Serializable {
    WALK, RUN
}

enum class TimerAction : Serializable {
    TICK, STAY
}

enum class WorkoutStepState : Serializable {
    READY, TICKING
}

enum class ControlState : Serializable {
    PLAY, PAUSE, STOP
}

enum class SkipControl : Serializable {
    FORWARD, BACK
}

enum class CompletionStatus : Serializable {
    STARTED, RUNNING, COMPLETED
}

data class WorkoutRemainingState(
    val workoutStepState: WorkoutStepState,
    val completionStatus: CompletionStatus,
    val workoutStep: WorkoutStep,
    val controlState: ControlState
) : Serializable

data class WorkoutStep(val type: WorkoutType, val time: Long) : Serializable
data class IndexedWorkoutSteps(val index: Int, val workout: ArrayList<WorkoutStep>) : Serializable

fun Context.observeResource(resourceName: Int): Observable<String> = Observable.create { emitter ->
    val inputStream = resources.openRawResource(resourceName)

    emitter.setCancellable {
        inputStream.close()
    }

    val s = Scanner(inputStream).useDelimiter("\\A")
    if (s.hasNext()) {
        emitter.onNext(s.next())
    }

    emitter.onComplete()
}

class WorkoutStepDeserializer : JsonDeserializer<WorkoutStep> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): WorkoutStep {
        if (json == null) {
            throw JSONException("Expected a workout step, but got null!")
        }

        val jObj = json.asJsonObject
        val typeS = jObj.get("type").asString
        val type = when (typeS) {
            "walk" -> WorkoutType.WALK
            else -> WorkoutType.RUN
        }
        val time = jObj.get("time").asLong
        return WorkoutStep(type, time)
    }
}

class WorkoutViewHolder constructor(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val weekText = itemView.findViewById(R.id.week_text) as TextView
    val dayText = itemView.findViewById(R.id.day_text) as TextView
    val runTimeText = itemView.findViewById(R.id.run_time_text) as TextView
    val completedImage = itemView.findViewById(R.id.completed_image) as ImageView
}

abstract class RxRecyclerViewAdapter<T, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    val itemClicks: PublishSubject<T> = PublishSubject.create()
    val indexedItemClicks: PublishSubject<Pair<Int, T>> = PublishSubject.create()
    val itemLongClicks: PublishSubject<T> = PublishSubject.create()
    val indexedItemLongClicks: PublishSubject<Pair<Int, T>> = PublishSubject.create()

    abstract fun getItem(position: Int): T

    override fun onBindViewHolder(holder: VH?, position: Int) {
        holder?.itemView?.setOnLongClickListener {
            val item = getItem(position)
            itemLongClicks.onNext(item)
            indexedItemLongClicks.onNext(Pair(position, item))
            true
        }
        holder?.itemView?.setOnClickListener {
            val item = getItem(position)
            itemClicks.onNext(item)
            indexedItemClicks.onNext(Pair(position, item))
        }
    }
}

fun formatNumber(number: Long): String {
    val ns = number.toString()
    if (ns.length == 1) {
        return "0" + ns
    } else {
        return ns
    }
}

fun formatTypeText(workoutType: WorkoutType): String {
    return when (workoutType) {
        WorkoutType.WALK -> "walk"
        WorkoutType.RUN -> "run"
    }
}

fun formatTimeText(minutes: Long, seconds: Long): String {
    var res = ""
    if (minutes != 0L) {
        res += "$minutes " + if (minutes == 1L) "minute" else "minutes"
    }
    if (seconds != 0L) {
        if (res.isNotEmpty()) {
            res += " and "
        }
        res += "$seconds " + if (seconds == 1L) "second" else "seconds"
    }

    return res
}

class WorkoutAdapter : RxRecyclerViewAdapter<List<WorkoutStep>, WorkoutViewHolder>() {
    val workouts = ArrayList<List<WorkoutStep>>()

    override fun getItem(position: Int): List<WorkoutStep> = workouts[position]

    fun addAll(workouts: List<List<WorkoutStep>>) {
        this.workouts.addAll(workouts)
        notifyDataSetChanged()
    }

    fun clear() {
        this.workouts.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): WorkoutViewHolder {
        val itemView = LayoutInflater
            .from(parent?.context)
            .inflate(R.layout.card_workout, parent, false)
        return WorkoutViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: WorkoutViewHolder?, position: Int) {
        super.onBindViewHolder(holder, position)
        val week = position / 3 + 1
        val day = position % 3 + 1
        val timeRunning = workouts[position].fold(0L) { total, (type, time) ->
            if (type == WorkoutType.RUN) total else total + time
        } / 60

        holder?.weekText?.text = "Week $week"
        holder?.dayText?.text = "Day $day"
        holder?.runTimeText?.text = "$timeRunning minutes running"
    }

    override fun getItemCount(): Int {
        return workouts.size
    }
}

class TimerFragment : RxFragment() {
    companion object {
        val TAG = "TimerFragment"
    }

    val disposable = CompositeDisposable()
    val adapter = WorkoutAdapter()

    override fun onCreateView(
        inflater: LayoutInflater?,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater?.inflate(R.layout.fragment_timer, container, false)

        val gson = GsonBuilder()
            .registerTypeAdapter(WorkoutStep::class.java, WorkoutStepDeserializer())
            .create()

        context.observeResource(R.raw.schedule)
            .map { gson.fromJson<List<List<WorkoutStep>>>(it) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                adapter.clear()
                adapter.addAll(it)
            }

        if (v != null) {
            val playButton = v.findViewById(R.id.playButton) as ImageView
            val stopButton = v.findViewById(R.id.stopButton) as ImageView
            val secondsText = v.findViewById(R.id.seconds_text) as TextView
            val minutesText = v.findViewById(R.id.minutes_text) as TextView
            val workoutTypeText = v.findViewById(R.id.workout_type_text) as TextView
            val workoutTimeText = v.findViewById(R.id.workout_time_text) as TextView
            val workoutRecycler = v.findViewById(R.id.workout_recycler) as RecyclerView
            val skipForwardButton = v.findViewById(R.id.skip_forward_button) as ImageView
            val skipBackButton = v.findViewById(R.id.skip_back_button) as ImageView

            workoutRecycler.setHasFixedSize(true)
            workoutRecycler.layoutManager = LinearLayoutManager(
                context, LinearLayoutManager.HORIZONTAL, false
            )
            workoutRecycler.adapter = adapter

            adapter.indexedItemLongClicks.subscribe { (index, workout) ->
                startTimer(context, IndexedWorkoutSteps(index, ArrayList(workout)))
            }.addTo(disposable)

            receiveRemaining(context)
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { (workoutStepState, completionStatus, workoutStep) ->
                    val minutes = workoutStep.time / 60
                    val seconds = workoutStep.time % 60

                    if (workoutStepState == WorkoutStepState.READY) {
                        workoutTypeText.text = "${formatTypeText(workoutStep.type)} for"
                        workoutTimeText.text = formatTimeText(minutes, seconds)
                    }

                    if (completionStatus == CompletionStatus.COMPLETED) {
                        workoutTypeText.text = "--"
                        workoutTimeText.text = "--"
                    }

                    minutesText.text = formatNumber(minutes)
                    secondsText.text = formatNumber(seconds)
                }.addTo(disposable)

            Observable.merge(
                skipForwardButton.clicks().map { SkipControl.FORWARD },
                skipBackButton.clicks().map { SkipControl.BACK }
            ).subscribe {
                skipTimer(context, it)
            }.addTo(disposable)

            playControls(playButton, stopButton).subscribe { control ->
                val newPlayIcon = when (control) {
                    ControlState.PLAY -> R.drawable.ic_pause_black_24dp
                    else -> R.drawable.ic_play_arrow_black_24dp
                }

                playButton.setImageDrawable(ContextCompat.getDrawable(activity, newPlayIcon))
                controlTimer(context, control)
            }.addTo(disposable)
        }

        return v
    }

    fun playControls(playButton: View, stopButton: View): Observable<ControlState> =
        Observable.merge(
            playButton.clicks().map { ControlState.PLAY },
            stopButton.clicks().map { ControlState.STOP }
        ).scan(ControlState.PAUSE) { acc: ControlState, s: ControlState ->
            when (s) {
                ControlState.STOP -> ControlState.STOP
                ControlState.PLAY -> when (acc) {
                    ControlState.PAUSE -> ControlState.PLAY
                    ControlState.STOP -> ControlState.PLAY
                    ControlState.PLAY -> ControlState.PAUSE
                }
                ControlState.PAUSE -> ControlState.PAUSE
            }
        }.distinctUntilChanged()

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
    }
}