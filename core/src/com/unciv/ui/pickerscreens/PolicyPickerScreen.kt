package com.unciv.ui.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.models.gamebasics.GameBasics
import com.unciv.models.gamebasics.Policy
import com.unciv.ui.utils.*


class PolicyPickerScreen(internal val civInfo: CivilizationInfo) : PickerScreen() {

    private var pickedPolicy: Policy? = null

    init {


        // Proof-of-concept for font generation
//        val generator = FreeTypeFontGenerator(Gdx.files.internal("skin/Esteban-Regular.ttf"))
//        val parameter = FreeTypeFontParameter()
//        parameter.size = 25
//        val font12 = generator.generateFont(parameter) // font size 12 pixels
//        generator.dispose() // don't forget to dispose to avoid memory leaks!
//        rightSideButton.style.font = font12

        val policies = civInfo.policies
        displayTutorials("PolicyPickerScreen")

        rightSideButton.setText("Adopt policy\r\n(" + policies.storedCulture + "/" + policies.getCultureNeededForNextPolicy() + ")")

        if (policies.freePolicies > 0) {
            rightSideButton.setText("Adopt free policy")
            closeButton.disable()
        }

        rightSideButton.addClickListener {
            civInfo.policies.adopt(pickedPolicy!!)

            // If we've moved to another screen in the meantime (great person pick, victory screen) ignore this
            if(game.screen is PolicyPickerScreen) game.screen = PolicyPickerScreen(civInfo)
        }



        topTable.row().pad(30f)

        for (branch in GameBasics.PolicyBranches.values) {
            if (branch.name == "Commerce") topTable.row()
            val branchGroup = Table()
            branchGroup.row().pad(20f)
            branchGroup.add(getPolicyButton(branch, false)).row()

            var currentRow = 1
            var currentColumn = 1
            val branchTable = Table()
            for (policy in branch.policies) {
                if (policy.name.endsWith("Complete")) continue
                if (policy.row > currentRow) {
                    branchTable.row()
                    currentRow++
                    currentColumn = 1
                }
                if (policy.column > currentColumn) {
                    branchTable.add().colspan(policy.column - currentColumn) // empty space
                }
                branchTable.add(getPolicyButton(policy, true)).colspan(2)
                currentColumn = policy.column + 2
            }
            branchTable.pack()
            branchGroup.add(branchTable).height(150f).row()

            branchGroup.add(getPolicyButton(branch.policies.last(), false)) // finisher

            topTable.add(branchGroup)
        }
        topTable.pack()
    }

    private fun pickPolicy(policy: Policy) {
        if (civInfo.policies.isAdopted(policy.name)
                || policy.name.endsWith("Complete")
                || !civInfo.policies.isAdoptable(policy)
                || !civInfo.policies.canAdoptPolicy()) {
            rightSideButton.disable()
        } else {
            rightSideButton.enable()
        }
        pickedPolicy = policy
        var policyText = policy.name + "\r\n" + policy.description + "\r\n"
        if (!policy.name.endsWith("Complete")){
            if(policy.requires!!.isNotEmpty())
                policyText += "Requires " + policy.requires!!.joinToString()
            else
                policyText += "Unlocked at "+policy.getBranch().era+" era"
        }
        descriptionLabel.setText(policyText)
    }

    private fun getPolicyButton(policy: Policy, image: Boolean): Button {
        var policyButton = Button(CameraStageBaseScreen.skin)
        if (image) {
            val policyImage = ImageGetter.getImage("PolicyIcons/" + policy.name.replace(" ", "_") + "_(Civ5).png")
            policyButton.add(policyImage).size(30f)
        } else {
            policyButton = TextButton(policy.name, CameraStageBaseScreen.skin)
        }

        if (civInfo.policies.isAdopted(policy.name)) { // existing
            policyButton.color = Color.GREEN
        } else if (!civInfo.policies.isAdoptable(policy))
        // non-available
        {
            policyButton.color = Color.GRAY
        }
        policyButton.addClickListener { pickPolicy(policy) }
        policyButton.pack()
        return policyButton
    }

}