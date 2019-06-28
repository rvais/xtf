package cz.xtf.junit5.listeners;

import cz.xtf.builder.builders.SecretBuilder;
import cz.xtf.builder.builders.secret.SecretType;
import cz.xtf.core.config.XTFConfig;
import cz.xtf.core.openshift.OpenShift;
import cz.xtf.core.openshift.OpenShifts;
import cz.xtf.core.waiting.SimpleWaiter;
import cz.xtf.junit5.config.JUnitConfig;
import io.fabric8.kubernetes.api.model.Secret;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public class ProjectCreator implements TestExecutionListener {
//	private static final OpenShift openShift = OpenShifts.admin();
	private static final OpenShift openShift = OpenShifts.master();

	@Override
	public void testPlanExecutionStarted(TestPlan testPlan) {
		if (openShift.getProject() == null) {
			openShift.createProjectRequest();
		}
		else {
			boolean deleted = openShift.deleteProject();
			// For multi-module maven projects, other modules may attempt to crate project requests immediately after this modules deleteProject
			if (deleted) {
				BooleanSupplier bs = () -> openShift.getProject() == null;
				new SimpleWaiter(bs, TimeUnit.MINUTES, 2, "Waiting for old project deletion").waitFor();
			}
			openShift.createProjectRequest();
		}

		if (!XTFConfig.get("xtf.config.oreg.name", "").trim().isEmpty() &&
				!XTFConfig.get("xtf.config.oreg.secret", "").trim().isEmpty())
		{
			Secret secret = null;
			final String secretName = XTFConfig.get("xtf.config.oreg.name");
			final String data = XTFConfig.get("xtf.config.oreg.secret");

			secret = openShift.getSecret(secretName);
			if (secret != null) {
				openShift.deleteSecret(secret);
			}

			secret = new SecretBuilder(secretName).setType(SecretType.DOCKERCFG)
					.addEncodedData(".dockerconfigjson", data).build();
			secret.getMetadata().setLabels(Collections.singletonMap(OpenShift.KEEP_LABEL, "keep"));

			openShift.createSecret(secret);

			for (String name:new String[]{"builder", "deployer", "default"}) {
				openShift.serviceAccounts().withName(name).edit().addNewImagePullSecret(secretName).done();
			}

		}
	}

	@Override
	public void testPlanExecutionFinished(TestPlan testPlan) {
		if (JUnitConfig.cleanOpenShift()) {
			boolean deleted = openShift.deleteProject();
			// For multi-module maven projects, other modules may attempt to crate project requests immediately after this modules deleteProject
			if (deleted) {
				BooleanSupplier bs = () -> openShift.getProject() == null;
				new SimpleWaiter(bs, TimeUnit.MINUTES, 2, "Waiting for old project deletion").waitFor();
			}
		}
	}
}
