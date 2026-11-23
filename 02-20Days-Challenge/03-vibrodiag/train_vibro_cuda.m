function train_vibro_cuda(inputDir, outDir)
% Fixed RBF-ridge classifier on group-level FFT/time-domain feature vectors.
% All model coefficient fits are on CUDA; MATLAB never silently falls back to CPU.
if nargin == 0
    here = fileparts(mfilename('fullpath'));
    inputDir = fullfile(here, 'inputs');
    outDir = fullfile(here, 'results');
end
assert(nargin == 0 || nargin == 2, 'Provide input/output folders or neither.');
g = gpuDevice();
assert(g.DeviceAvailable && g.DeviceSupported, 'A supported CUDA device is required.');
fprintf('MATLAB GPU: %s | compute capability %s | toolkit %.2f\n', g.Name, g.ComputeCapability, g.ToolkitVersion);
if ~isfolder(outDir), mkdir(outDir); end

conditions = ["C1_NORMAL_LOW"; "C2_NORMAL_HIGH"; "C3_IMBALANCE"; "C4_BLOCKED"; "C5_HAIRDRYER"];
variants = ["clean", "white_20db", "white_10db", "white_0db"];
ref = readtable(fullfile(inputDir, 'clean.csv'), 'TextType', 'string');
headers = ref.Properties.VariableNames;
assert(width(ref) == 34 && all(strcmp(headers(1:2), {'condition_id','group_index'})), 'Unexpected feature-table schema.');
assert(height(ref) == 75, 'Expected exactly 75 numbered training groups.');
features = headers(3:end);
label = string(ref.condition_id);
group = double(ref.group_index);
assert(all(ismember(label, conditions)) && all(ismember(group, 1:15)), 'Invalid condition or group.');
assert(numel(unique(label + "/" + string(group))) == 75, 'Duplicate group key.');
X = cell(1, numel(variants));
X{1} = feature_matrix(ref, features);
for s = 2:numel(variants)
    tbl = readtable(fullfile(inputDir, variants(s) + ".csv"), 'TextType', 'string');
    assert(isequal(headers, tbl.Properties.VariableNames) && isequal(label, string(tbl.condition_id)) && ...
        isequal(group, double(tbl.group_index)), 'Variant table must match the clean group order and schema.');
    X{s} = feature_matrix(tbl, features);
end
[~, y] = ismember(label, conditions);
% Hold out each 3-group block per class. Each entire recording is one row;
% no windows/augmentations from the same group are shared with training.
fold = ceil(group / 3);
fixedLambda = 0.10;
predicted = zeros(height(ref), numel(variants));
fitTimes = zeros(6, 1);
for f = 1:5
    test = fold == f;
    assert(nnz(test) == 15 && nnz(~test) == 60, 'Unexpected fold size.');
    [model, fitTimes(f)] = fit_on_cuda(X{1}(~test,:), y(~test), features, conditions, fixedLambda);
    for s = 1:numel(variants)
        predicted(test, s) = predict_vibro_cuda(model, X{s}(test,:));
    end
end
assert(all(predicted(:) > 0), 'Each row must receive exactly one held-out prediction per scenario.');

summary = table(variants', sum(predicted == y, 1)', repmat(height(ref),numel(variants),1), ...
    mean(predicted == y, 1)', 'VariableNames', {'scenario','correct','tested','accuracy'});
writetable(summary, fullfile(outDir, 'cv_summary.csv'));
predictionTable = table(label, group, fold, 'VariableNames', {'true_condition','group_index','validation_fold'});
for s = 1:numel(variants)
    predictionTable.(variants(s)) = conditions(predicted(:,s));
end
writetable(predictionTable, fullfile(outDir, 'cv_predictions.csv'));
C = accumarray([y predicted(:,1)], 1, [5 5]);
cmTable = array2table(C, 'VariableNames', cellstr(conditions));
cmTable = addvars(cmTable,conditions,'Before',1,'NewVariableNames','true_condition');
writetable(cmTable, fullfile(outDir, 'cv_confusion_clean.csv'));

% This final model uses all existing groups. Its accuracy on these 75 rows is
% deliberately not reported as blind-test accuracy. Freeze before fresh trials.
[model, fitTimes(6)] = fit_on_cuda(X{1}, y, features, conditions, fixedLambda);
model.gpuName = g.Name;
model.computeCapability = g.ComputeCapability;
model.toolkitVersion = g.ToolkitVersion;
model.matlabVersion = version;
model.trainingCount = height(ref);
model.protocol = 'Blocked group-number 5-fold validation within one session; final fit on all 75 groups.';
save(fullfile(outDir, 'vibro_cuda_model.mat'), 'model');
metadata = struct('gpu', g.Name, 'computeCapability', g.ComputeCapability, ...
    'matlabVersion', version, 'cudaToolkitVersion', g.ToolkitVersion, 'coefficientsFitOnGpu', true, ...
    'trainingGroups', height(ref), 'rawFeatureCount', numel(features), 'retainedFeatureCount', nnz(model.keep), ...
    'lambda', fixedLambda, 'gamma', model.gamma, 'foldFitSeconds', fitTimes', ...
    'validation', model.protocol, 'independentBlindTrials', 0);
fid = fopen(fullfile(outDir, 'run_metadata.json'), 'w', 'n', 'UTF-8');
assert(fid >= 0, 'Cannot write metadata.');
fprintf(fid,'%s\n',jsonencode(metadata,'PrettyPrint',true));
fclose(fid);

f1 = figure('Visible','off','Color','white','Position',[0 0 700 450]);
imagesc(C); colorbar; axis image;
xticks(1:5); yticks(1:5); xticklabels(compose('C%d',1:5)); yticklabels(compose('C%d',1:5));
xlabel('Predicted condition','Color','k'); ylabel('True condition','Color','k');
title('Clean recordings: grouped cross-validation','Color','k');
set(gca,'Color','w','XColor','k','YColor','k');
cb = colorbar; cb.Color = 'k';
for a = 1:5
    for b = 1:5
        if C(a,b) >= 8, contrast = 'black'; else, contrast = 'white'; end
        text(b,a,string(C(a,b)),'HorizontalAlignment','center','Color',contrast);
    end
end
exportgraphics(gca,fullfile(outDir,'cv_confusion_clean.png'),'Resolution',160); close(f1);
f2 = figure('Visible','off','Color','white','Position',[0 0 700 450]);
bar(100 * summary.accuracy,'FaceColor',[0.20 0.41 0.66],'EdgeColor','none'); ylim([0 105]); grid on;
set(gca,'Color','w','XColor','k','YColor','k','GridColor',[0.55 0.55 0.55]);
xticklabels({'Clean','White +20 dB','White +10 dB','White 0 dB'});
ylabel('Held-out accuracy (%)','Color','k'); title('White noise on audio only (same 75 held-out groups)','Color','k');
text(1:4,100 * summary.accuracy + 2,compose('%d/%d',summary.correct,summary.tested),'HorizontalAlignment','center','Color','k');
exportgraphics(gca,fullfile(outDir,'cv_noise_comparison.png'),'Resolution',160); close(f2);
for s = 1:height(summary)
    fprintf('%s: %d/%d = %.2f%%\n', summary.scenario(s), summary.correct(s), summary.tested(s), 100 * summary.accuracy(s));
end
fprintf('Wrote CUDA model and validation artifacts: %s\n',outDir);
end

function A = feature_matrix(tbl, features)
A = nan(height(tbl),numel(features));
for j = 1:numel(features)
    col = tbl.(features{j});
    if isnumeric(col)
        A(:,j) = double(col);
    else
        A(:,j) = str2double(string(col));
    end
end
A(~isfinite(A)) = NaN;
end

function [model, duration] = fit_on_cuda(X,y,features,conditions,lambda)
% Prepare only with statistics of the current training partition.
keep = any(isfinite(X),1);
assert(any(keep),'No usable features in this training partition.');
X = X(:,keep);
center = median(X,1,'omitnan');
for j = 1:size(X,2), X(~isfinite(X(:,j)),j) = center(j); end
mu = mean(X,1); sigma = std(X,0,1); sigma(sigma < 1e-12) = 1;
clock = tic;
Z = gpuArray(single((X-mu)./sigma));
Ycpu = single(y == (1:numel(conditions)));
Y = gpuArray(Ycpu);
gamma = 1/size(Z,2);
D = sum(Z.^2,2) + sum(Z.^2,2)' - 2 * (Z*Z');
K = exp(-single(gamma) * max(D,0));
A = (K + single(lambda) * gpuArray(eye(size(K,1),'single'))) \ Y;
assert(isa(A,'gpuArray'), 'Fitting did not execute on CUDA.');
model = struct('keep',keep,'fill',center,'mu',mu,'sigma',sigma,'gamma',gamma,'trainZ',gather(Z), ...
    'alpha',gather(A),'conditions',conditions,'featureNames',{features},'lambda',lambda);
assert(all(isfinite(model.alpha),'all'), 'GPU solver produced invalid coefficients.');
duration = toc(clock);
end
