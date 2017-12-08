package com.komputation.cpu.layers.recurrent

import com.komputation.cpu.functions.add
import com.komputation.cpu.functions.getColumn
import com.komputation.cpu.functions.setColumn
import com.komputation.cpu.layers.BaseCpuForwardLayer
import com.komputation.cpu.layers.combination.CpuAdditionCombination
import com.komputation.cpu.layers.forward.activation.CpuActivationLayer
import com.komputation.cpu.layers.forward.projection.CpuWeightingLayer
import com.komputation.layers.Resourceful
import com.komputation.optimization.Optimizable

class CpuRecurrentLayer(
    name : String?,
    private val minimumSteps : Int,
    private val maximumSteps : Int,
    private val hiddenDimension : Int,
    private val inputWeighting : CpuWeightingLayer,
    private val initialState : FloatArray,
    private val previousHiddenStateWeighting: Series,
    private val additions : Array<CpuAdditionCombination>,
    private val bias: Series?,
    private val activations : Array<CpuActivationLayer>) : BaseCpuForwardLayer(name), Resourceful, Optimizable {

    override var forwardResult = FloatArray(0)
    override val numberOutputRows
        get() = this.inputWeighting.numberOutputRows
    override val numberOutputColumns
        get() = this.inputWeighting.numberOutputColumns
    override val backwardResult
        get() = this.inputWeighting.backwardResult
    override val numberInputRows
        get() = this.inputWeighting.numberInputRows
    override val numberInputColumns
        get() = this.inputWeighting.numberInputColumns

    override fun acquire(maximumBatchSize: Int) {
        this.inputWeighting.acquire(maximumBatchSize)
    }

    override fun release() {
        this.inputWeighting.release()
    }

    private val numberPossibleLengths = this.maximumSteps - this.minimumSteps + 1
    private val possibleLengths = Array(this.numberPossibleLengths) { index -> this.minimumSteps + index }

    private val stepWeightedInput = FloatArray(this.hiddenDimension)
    private val stepChain = FloatArray(this.hiddenDimension)
    private val forwardResultsOverPossibleLengths = Array(this.numberPossibleLengths) { index -> FloatArray(this.possibleLengths[index] * this.hiddenDimension) }
    private val backwardPreactivationOverPossibleLengths = Array(this.numberPossibleLengths) { index -> FloatArray(this.possibleLengths[index] * this.hiddenDimension) }

    // h_t = f(Uh + Wx)
    override fun forward(withinBatch : Int, numberInputColumns : Int, input: FloatArray, isTraining : Boolean): FloatArray {
        val weightedInput = this.inputWeighting.forward(withinBatch, numberInputColumns, input, isTraining)

        var previousHiddenState = this.initialState

        this.forwardResult = this.forwardResultsOverPossibleLengths[this.numberInputColumns - this.minimumSteps]

        for (step in 0 until numberInputColumns) {
            getColumn(weightedInput, step, this.hiddenDimension, this.stepWeightedInput)

            val weightedPreviousHiddenState = this.previousHiddenStateWeighting.forwardStep(withinBatch, step, 1, previousHiddenState, isTraining)

            val addition = this.additions[step].forward(this.stepWeightedInput, weightedPreviousHiddenState)

            val hiddenState = this.activations[step].forward(withinBatch, 1, addition, isTraining)

            val finalHiddenState =
                if(this.bias != null)
                    this.bias.forwardStep(withinBatch, step, 1, hiddenState, isTraining)
                else
                    hiddenState

            setColumn(finalHiddenState, step, this.hiddenDimension, this.forwardResult)

            previousHiddenState = finalHiddenState
        }

        return this.forwardResult
    }

    /*
              y1    y2           yT
              |     |            |
        h0 -> h1 -> h2 -> ... -> hT
              |     |            |
              p1    p2           pT

          dy_2/dWx_2 + dh_3/dWx_2
        = dy_2/dh_2 * dh_2/dWx_2 + dh_3/dWx_2 * dh_2/dWx_2
        = [ dy_2/dh_2 * df(Uh_1+Wx_2)/dWx_2 ] +
                       =dh2
          [ df(Uh_2+Wx_3)/df(Uh_1+Wx_2) * df(Uh_1+Wx_2)/dWx_2 ]
            =dh3          =dh2            =dh2
        = [ dy_2/df(Uh_1+Wx_2) * df(Uh_1+Wx_2)/d(Uh_1+Wx_2) * dUh_1+Wx_2/dWx_2 ] +
                                =dh2
          [ df(Uh_2+Wx_3)/df(Uh_1+Wx_2) * df(Uh_1+Wx_2)/d(Uh_1+Wx_2) * dUh_1+Wx_2/dWx_2 ]
            =dh3          =dh2            =dh2

        = [ dy_2/df(Uh_1+Wx_2) + df(Uh_2+Wx_3)/df(Uh_1+Wx_2) ] * df(Uh_1+Wx_2)/d(Uh_1+Wx_2) * dUh_1+Wx_2/dWx_2
     */
    override fun backward(withinBatch: Int, chain: FloatArray) : FloatArray {
        val backwardPreactivation = this.backwardPreactivationOverPossibleLengths[this.numberInputColumns - this.minimumSteps]

        var previousBackwardPreviousHiddenState : FloatArray? = null

        val lastStep = this.numberInputColumns - 1

        for (step in lastStep downTo 0) {

            getColumn(chain, step, this.hiddenDimension, this.stepChain)

            if(step < lastStep) {
                add(this.stepChain, previousBackwardPreviousHiddenState!!, this.stepChain, this.hiddenDimension)
            }

            // dh_t / d(Wx_t + Uh_(t-1) + b) = df(Wx_t + Uh_(t-1) + b) / d(Wx_t + Uh_(t-1) + b)
            val stepBackwardPreActivation = this.activations[step].backward(withinBatch, this.stepChain)

            // d(Wx_t + Uh_(t-1) + b) / dWx_t
            setColumn(stepBackwardPreActivation, step, this.hiddenDimension, backwardPreactivation)

            // d(Wx_t + Uh_(t-1) + b) / dUh_(t-1)
            val backwardPreviousHiddenState = this.previousHiddenStateWeighting.backwardStep(withinBatch, step, stepBackwardPreActivation)
            previousBackwardPreviousHiddenState = backwardPreviousHiddenState

            // d(Wx_t + Uh_(t-1) + b) / db
            this.bias?.backwardStep(withinBatch, step, backwardPreactivation)
        }

        this.previousHiddenStateWeighting.backwardSeries()
        this.bias?.backwardSeries()

        return this.inputWeighting.backward(withinBatch, backwardPreactivation)
    }

    override fun optimize(batchSize: Int) {
        this.inputWeighting.optimize(batchSize)
        this.previousHiddenStateWeighting.optimize(batchSize)
        this.bias?.optimize(batchSize)
    }

}